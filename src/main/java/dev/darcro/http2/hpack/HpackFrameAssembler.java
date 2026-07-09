package dev.darcro.http2.hpack;

import dev.darcro.http2.frame.ByteSequence;
import dev.darcro.http2.frame.ContinuationFrame;
import dev.darcro.http2.frame.HeadersFrame;
import dev.darcro.http2.frame.Http2Frame;
import dev.darcro.http2.frame.PushPromiseFrame;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/**
 * Reassembles HTTP/2 field-block fragments and decodes them with an HPACK
 * decoder. Feed every inbound frame to detect illegal interleaving. This class
 * is not thread-safe.
 */
public final class HpackFrameAssembler {
    private final HpackDecoder decoder;
    private final HpackFrameSequenceRecoveryPolicy sequenceRecoveryPolicy;
    private final List<ByteSequence> fragments = new ArrayList<>();
    private final List<HpackFrameSequenceRecoveryEvent> sequenceRecoveryEvents =
            new ArrayList<>();
    private HeaderBlockOrigin origin;
    private int streamId;
    private boolean endStream;
    private int promisedStreamId;
    private long encodedLength;
    private boolean active;
    private boolean failed;

    /** Creates an assembler with the default HPACK resource limits. */
    public HpackFrameAssembler() {
        this(new HpackDecoder(), HpackFrameSequenceRecoveryPolicy.FAIL_FAST);
    }

    /** Creates an assembler with the supplied HPACK resource limits. */
    public HpackFrameAssembler(HpackDecoderConfig config) {
        this(new HpackDecoder(Objects.requireNonNull(config, "config")),
                HpackFrameSequenceRecoveryPolicy.FAIL_FAST);
    }

    /**
     * Creates an assembler with the default HPACK resource limits and supplied
     * sequence recovery policy.
     */
    public HpackFrameAssembler(HpackFrameSequenceRecoveryPolicy sequenceRecoveryPolicy) {
        this(new HpackDecoder(), sequenceRecoveryPolicy);
    }

    /**
     * Creates an assembler with the supplied HPACK resource limits and sequence
     * recovery policy.
     */
    public HpackFrameAssembler(HpackDecoderConfig config,
                               HpackFrameSequenceRecoveryPolicy sequenceRecoveryPolicy) {
        this(new HpackDecoder(Objects.requireNonNull(config, "config")),
                sequenceRecoveryPolicy);
    }

    private HpackFrameAssembler(HpackDecoder decoder,
                                HpackFrameSequenceRecoveryPolicy sequenceRecoveryPolicy) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
        this.sequenceRecoveryPolicy = Objects.requireNonNull(sequenceRecoveryPolicy,
                "sequenceRecoveryPolicy");
    }

    /** Restores an assembler and its decoder under caller-supplied limits. */
    public static HpackFrameAssembler restore(HpackFrameAssemblerSnapshot snapshot,
                                              HpackDecoderConfig config)
            throws HpackSnapshotException {
        return restore(snapshot, config, HpackFrameSequenceRecoveryPolicy.FAIL_FAST);
    }

    /**
     * Restores an assembler and its decoder under caller-supplied limits and
     * sequence recovery policy.
     */
    public static HpackFrameAssembler restore(HpackFrameAssemblerSnapshot snapshot,
                                              HpackDecoderConfig config,
                                              HpackFrameSequenceRecoveryPolicy
                                                      sequenceRecoveryPolicy)
            throws HpackSnapshotException {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(config, "config");
        Objects.requireNonNull(sequenceRecoveryPolicy, "sequenceRecoveryPolicy");
        HpackDecoder decoder = HpackDecoder.restore(snapshot.decoderSnapshot(), config);
        if (snapshot.incompleteBlock().length() > config.maxEncodedHeaderBlockSize()) {
            throw new HpackSnapshotException(HpackSnapshotErrorReason.CONFIGURATION_LIMIT,
                    -1, "Incomplete field block exceeds local configuration");
        }

        HpackFrameAssembler assembler = new HpackFrameAssembler(decoder,
                sequenceRecoveryPolicy);
        if (snapshot.active()) {
            HeaderBlockOrigin restoredOrigin = snapshot.origin().orElse(null);
            if (restoredOrigin == null) {
                throw invalidAssemblerSnapshot("Active snapshot has no origin");
            }
            int restoredPromisedStream = snapshot.promisedStreamId().orElse(0);
            validateRestoredAssembler(snapshot, restoredOrigin, restoredPromisedStream);
            assembler.origin = restoredOrigin;
            assembler.streamId = snapshot.streamId();
            assembler.endStream = snapshot.endStream();
            assembler.promisedStreamId = restoredPromisedStream;
            assembler.encodedLength = snapshot.incompleteBlock().length();
            assembler.fragments.add(ByteSequence.wrap(snapshot.incompleteBlockBytes()));
            assembler.active = true;
        } else if (snapshot.origin().isPresent() || snapshot.streamId() != 0
                || snapshot.endStream() || snapshot.promisedStreamId().isPresent()
                || !snapshot.incompleteBlock().isEmpty()) {
            throw invalidAssemblerSnapshot("Inactive snapshot contains field-block state");
        }
        return assembler;
    }

    /** Returns the resource limits applied to the owned decoder. */
    public HpackDecoderConfig config() {
        return decoder.config();
    }

    /** Returns the bytes currently occupied by dynamic-table entries. */
    public int dynamicTableSize() {
        return decoder.dynamicTableSize();
    }

    /** Returns the latest applied protocol maximum for the dynamic table. */
    public int maxDynamicTableSize() {
        return decoder.maxDynamicTableSize();
    }

    /**
     * Applies a SETTINGS_HEADER_TABLE_SIZE limit after connection code has
     * determined that the setting takes effect.
     */
    public void updateMaxDynamicTableSize(int maximumSize) {
        if (failed) {
            throw new IllegalStateException("HPACK frame assembler is in a failed state");
        }
        decoder.updateMaxDynamicTableSize(maximumSize);
    }

    public boolean failed() {
        return failed;
    }

    /** Returns the policy used for invalid field-block frame sequencing. */
    public HpackFrameSequenceRecoveryPolicy sequenceRecoveryPolicy() {
        return sequenceRecoveryPolicy;
    }

    /** Returns immutable diagnostics for recovered frame sequence errors. */
    public List<HpackFrameSequenceRecoveryEvent> recoveryEvents() {
        return List.copyOf(sequenceRecoveryEvents);
    }

    /** Returns true when any frame sequence errors have been recovered. */
    public boolean recoveredSequenceErrors() {
        return !sequenceRecoveryEvents.isEmpty();
    }

    /** Clears recovered frame sequence diagnostics without changing decoder state. */
    public void clearRecoveryEvents() {
        sequenceRecoveryEvents.clear();
    }

    /** Captures the decoder and any field block held between frame inputs. */
    public HpackFrameAssemblerSnapshot snapshot() {
        if (failed || decoder.failed()) {
            throw new IllegalStateException("Cannot snapshot a failed HPACK assembler");
        }
        if (encodedLength > Integer.MAX_VALUE) {
            throw new IllegalStateException("Incomplete field block is too large to snapshot");
        }
        byte[] incomplete = new byte[(int) encodedLength];
        int offset = 0;
        for (ByteSequence fragment : fragments) {
            fragment.copyTo(incomplete, offset);
            offset += fragment.length();
        }
        return new HpackFrameAssemblerSnapshot(decoder.snapshot(), active, origin,
                streamId, endStream, promisedStreamId, incomplete);
    }

    public Optional<DecodedHeaderBlock> accept(Http2Frame frame)
            throws HpackDecodingException, HpackFrameSequenceException {
        Objects.requireNonNull(frame, "frame");
        if (failed) {
            throw sequenceError(HpackFrameSequenceReason.ASSEMBLER_FAILED,
                    frame.streamId(), "HPACK frame assembler is in a failed state");
        }

        if (active) {
            return acceptActive(frame);
        }
        return acceptIdle(frame);
    }

    private Optional<DecodedHeaderBlock> acceptActive(Http2Frame frame)
            throws HpackDecodingException, HpackFrameSequenceException {
        if (!(frame instanceof ContinuationFrame continuation)) {
            if (recoveringSequenceErrors()) {
                recoverSequenceError(HpackFrameSequenceReason.INTERLEAVED_FRAME,
                        frame.streamId(),
                        "A field block cannot be interleaved with another frame");
                clearBlock();
                return acceptIdle(frame);
            }
            fail();
            throw sequenceError(HpackFrameSequenceReason.INTERLEAVED_FRAME,
                    frame.streamId(),
                    "A field block cannot be interleaved with another frame");
        }
        if (continuation.streamId() != streamId) {
            if (recoveringSequenceErrors()) {
                recoverSequenceError(HpackFrameSequenceReason.WRONG_STREAM,
                        continuation.streamId(),
                        "CONTINUATION frame belongs to a different stream");
                clearBlock();
                return Optional.empty();
            }
            fail();
            throw sequenceError(HpackFrameSequenceReason.WRONG_STREAM,
                    continuation.streamId(),
                    "CONTINUATION frame belongs to a different stream");
        }
        append(continuation.headerBlockFragment());
        if (continuation.endHeaders()) {
            return Optional.of(complete());
        }
        return Optional.empty();
    }

    private Optional<DecodedHeaderBlock> acceptIdle(Http2Frame frame)
            throws HpackDecodingException, HpackFrameSequenceException {
        if (frame instanceof ContinuationFrame) {
            if (recoveringSequenceErrors()) {
                recoverSequenceError(HpackFrameSequenceReason.UNEXPECTED_CONTINUATION,
                        frame.streamId(), "CONTINUATION has no preceding field block");
                return Optional.empty();
            }
            fail();
            throw sequenceError(HpackFrameSequenceReason.UNEXPECTED_CONTINUATION,
                    frame.streamId(), "CONTINUATION has no preceding field block");
        }
        if (frame instanceof HeadersFrame headers) {
            begin(HeaderBlockOrigin.HEADERS, headers.streamId(), headers.endStream(), 0,
                    headers.headerBlockFragment());
            return headers.endHeaders() ? Optional.of(complete()) : Optional.empty();
        }
        if (frame instanceof PushPromiseFrame pushPromise) {
            begin(HeaderBlockOrigin.PUSH_PROMISE, pushPromise.streamId(), false,
                    pushPromise.promisedStreamId(), pushPromise.headerBlockFragment());
            return pushPromise.endHeaders() ? Optional.of(complete()) : Optional.empty();
        }
        return Optional.empty();
    }

    private boolean recoveringSequenceErrors() {
        return sequenceRecoveryPolicy == HpackFrameSequenceRecoveryPolicy.RECOVER;
    }

    private void recoverSequenceError(HpackFrameSequenceReason reason, int streamId,
                                      String message) {
        sequenceRecoveryEvents.add(new HpackFrameSequenceRecoveryEvent(reason, streamId,
                message));
    }

    private void begin(HeaderBlockOrigin origin, int streamId, boolean endStream,
                       int promisedStreamId, ByteSequence fragment)
            throws HpackDecodingException {
        this.origin = origin;
        this.streamId = streamId;
        this.endStream = endStream;
        this.promisedStreamId = promisedStreamId;
        this.active = true;
        append(fragment);
    }

    private void append(ByteSequence fragment) throws HpackDecodingException {
        fragments.add(fragment);
        encodedLength += fragment.length();
        if (encodedLength > decoder.config().maxEncodedHeaderBlockSize()) {
            try {
                decoder.decodeFragments(fragments, encodedLength);
            } catch (HpackDecodingException exception) {
                int failedStreamId = streamId;
                fail();
                throw exception.withStreamId(failedStreamId);
            }
        }
    }

    private DecodedHeaderBlock complete() throws HpackDecodingException {
        try {
            HpackDecodeResult decoded = decoder.decodeFragments(fragments, encodedLength);
            DecodedHeaderBlock result = new DecodedHeaderBlock(origin, streamId, endStream,
                    origin == HeaderBlockOrigin.PUSH_PROMISE
                            ? OptionalInt.of(promisedStreamId) : OptionalInt.empty(),
                    decoded.fields(), decoded.recoveryEvents());
            clearBlock();
            return result;
        } catch (HpackDecodingException exception) {
            int failedStreamId = streamId;
            fail();
            throw exception.withStreamId(failedStreamId);
        }
    }

    private void clearBlock() {
        fragments.clear();
        origin = null;
        streamId = 0;
        endStream = false;
        promisedStreamId = 0;
        encodedLength = 0;
        active = false;
    }

    private void fail() {
        clearBlock();
        failed = true;
    }

    private static HpackFrameSequenceException sequenceError(
            HpackFrameSequenceReason reason, int streamId, String message) {
        return new HpackFrameSequenceException(reason, streamId, message);
    }

    private static void validateRestoredAssembler(HpackFrameAssemblerSnapshot snapshot,
                                                   HeaderBlockOrigin origin,
                                                   int promisedStreamId)
            throws HpackSnapshotException {
        if (snapshot.streamId() <= 0) {
            throw invalidAssemblerSnapshot("Active snapshot has an invalid stream ID");
        }
        if (origin == HeaderBlockOrigin.HEADERS && promisedStreamId != 0) {
            throw invalidAssemblerSnapshot("HEADERS snapshot has a promised stream ID");
        }
        if (origin == HeaderBlockOrigin.PUSH_PROMISE
                && (promisedStreamId <= 0 || snapshot.endStream())) {
            throw invalidAssemblerSnapshot("Invalid PUSH_PROMISE snapshot metadata");
        }
    }

    private static HpackSnapshotException invalidAssemblerSnapshot(String message) {
        return new HpackSnapshotException(HpackSnapshotErrorReason.INVALID_ASSEMBLER_STATE,
                -1, message);
    }
}
