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
    private final List<ByteSequence> fragments = new ArrayList<>();
    private HeaderBlockOrigin origin;
    private int streamId;
    private boolean endStream;
    private int promisedStreamId;
    private long encodedLength;
    private boolean active;
    private boolean failed;

    public HpackFrameAssembler(HpackDecoder decoder) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    /** Restores an assembler and its decoder under caller-supplied limits. */
    public static HpackFrameAssembler restore(HpackFrameAssemblerSnapshot snapshot,
                                              HpackDecoderConfig config)
            throws HpackSnapshotException {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(config, "config");
        HpackDecoder decoder = HpackDecoder.restore(snapshot.decoderSnapshot(), config);
        if (snapshot.incompleteBlock().length() > config.maxEncodedHeaderBlockSize()) {
            throw new HpackSnapshotException(HpackSnapshotErrorReason.CONFIGURATION_LIMIT,
                    -1, "Incomplete field block exceeds local configuration");
        }

        HpackFrameAssembler assembler = new HpackFrameAssembler(decoder);
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

    public HpackDecoder decoder() {
        return decoder;
    }

    public boolean failed() {
        return failed;
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
            if (!(frame instanceof ContinuationFrame continuation)) {
                fail();
                throw sequenceError(HpackFrameSequenceReason.INTERLEAVED_FRAME,
                        frame.streamId(),
                        "A field block cannot be interleaved with another frame");
            }
            if (continuation.streamId() != streamId) {
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

        if (frame instanceof ContinuationFrame) {
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
            List<HpackHeaderField> fields = decoder.decodeFragments(fragments, encodedLength);
            DecodedHeaderBlock result = new DecodedHeaderBlock(origin, streamId, endStream,
                    origin == HeaderBlockOrigin.PUSH_PROMISE
                            ? OptionalInt.of(promisedStreamId) : OptionalInt.empty(),
                    fields);
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
