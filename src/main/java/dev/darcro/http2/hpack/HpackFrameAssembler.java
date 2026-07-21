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
 * Passive reassembler and analyzer for one observed HTTP/2 traffic direction.
 * This class is not thread-safe and never enters a terminal failed state.
 */
public final class HpackFrameAssembler {
    private final HpackDecoder decoder;
    private final List<ByteSequence> fragments = new ArrayList<>();
    private final List<HpackDiagnostic> diagnostics = new ArrayList<>();
    private HeaderBlockOrigin origin;
    private int streamId;
    private boolean endStream;
    private int promisedStreamId;
    private long encodedLength;
    private boolean active;
    private boolean discarding;

    public HpackFrameAssembler() {
        this(HpackDecoderConfig.defaults());
    }

    public HpackFrameAssembler(HpackDecoderConfig config) {
        this(new HpackDecoder(config));
    }

    private HpackFrameAssembler(HpackDecoder decoder) {
        this.decoder = Objects.requireNonNull(decoder, "decoder");
    }

    public static HpackFrameAssembler atConnectionStart() {
        return atConnectionStart(HpackDecoderConfig.defaults());
    }

    public static HpackFrameAssembler atConnectionStart(HpackDecoderConfig config) {
        return new HpackFrameAssembler(HpackDecoder.atConnectionStart(config));
    }

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
            int restoredPromisedStream = snapshot.promisedStreamId().orElse(0);
            validateRestoredAssembler(snapshot, restoredOrigin, restoredPromisedStream);
            assembler.origin = restoredOrigin;
            assembler.streamId = snapshot.streamId();
            assembler.endStream = snapshot.endStream();
            assembler.promisedStreamId = restoredPromisedStream;
            assembler.encodedLength = snapshot.incompleteBlock().length();
            if (!snapshot.incompleteBlock().isEmpty()) {
                assembler.fragments.add(ByteSequence.wrap(snapshot.incompleteBlockBytes()));
            }
            assembler.active = true;
        } else if (snapshot.discarding()) {
            if (snapshot.streamId() <= 0 || snapshot.origin().isPresent()
                    || snapshot.endStream() || snapshot.promisedStreamId().isPresent()
                    || !snapshot.incompleteBlock().isEmpty()) {
                throw invalidAssemblerSnapshot("Invalid discarding assembler state");
            }
            assembler.streamId = snapshot.streamId();
            assembler.discarding = true;
        } else if (snapshot.origin().isPresent() || snapshot.streamId() != 0
                || snapshot.endStream() || snapshot.promisedStreamId().isPresent()
                || !snapshot.incompleteBlock().isEmpty()) {
            throw invalidAssemblerSnapshot("Inactive snapshot contains field-block state");
        }
        return assembler;
    }

    public HpackDecoderConfig config() {
        return decoder.config();
    }

    public HpackContextCompleteness contextCompleteness() {
        return decoder.contextCompleteness();
    }

    public int dynamicTableSize() {
        return decoder.dynamicTableSize();
    }

    public int maxDynamicTableSize() {
        return decoder.maxDynamicTableSize();
    }

    public void updateMaxDynamicTableSize(int maximumSize) {
        decoder.updateMaxDynamicTableSize(maximumSize);
    }

    public HpackFrameAssemblerSnapshot snapshot() {
        if (encodedLength > Integer.MAX_VALUE) {
            throw new IllegalStateException("Incomplete field block is too large to snapshot");
        }
        byte[] incomplete = new byte[(int) encodedLength];
        int offset = 0;
        for (ByteSequence fragment : fragments) {
            fragment.copyTo(incomplete, offset);
            offset += fragment.length();
        }
        return new HpackFrameAssemblerSnapshot(decoder.snapshot(), active, discarding,
                origin, streamId, endStream, promisedStreamId, incomplete);
    }

    public HpackFrameAnalysis accept(Http2Frame frame) {
        Objects.requireNonNull(frame, "frame");
        diagnostics.clear();
        if (discarding) {
            return acceptDiscarding(frame);
        }
        if (active) {
            return acceptActive(frame);
        }
        return acceptIdle(frame);
    }

    private HpackFrameAnalysis acceptDiscarding(Http2Frame frame) {
        if (frame instanceof ContinuationFrame continuation
                && continuation.streamId() == streamId) {
            if (continuation.endHeaders()) {
                clearBlock();
            }
            return result(HpackFrameAnalysisStatus.BLOCK_DISCARDED, null);
        }

        if (frame instanceof ContinuationFrame continuation) {
            emit(HpackDiagnosticReason.WRONG_STREAM_CONTINUATION,
                    continuation.streamId(),
                    "CONTINUATION belongs to a different discarded field block");
            clearBlock();
            if (!continuation.endHeaders()) {
                discarding = true;
                streamId = continuation.streamId();
            }
            return result(HpackFrameAnalysisStatus.BLOCK_DISCARDED, null);
        }

        emit(HpackDiagnosticReason.INTERLEAVED_FRAME, frame.streamId(),
                "Frame interrupted a discarded field block");
        clearBlock();
        return acceptIdle(frame);
    }

    private HpackFrameAnalysis acceptActive(Http2Frame frame) {
        if (!(frame instanceof ContinuationFrame continuation)) {
            emit(HpackDiagnosticReason.INTERLEAVED_FRAME, frame.streamId(),
                    "A field block was interrupted by another frame");
            diagnostics.addAll(decoder.discardObservedContext(
                    "Incomplete field block was abandoned"));
            clearBlock();
            if (frame instanceof HeadersFrame || frame instanceof PushPromiseFrame) {
                return acceptIdle(frame);
            }
            return result(HpackFrameAnalysisStatus.BLOCK_DISCARDED, null);
        }
        if (continuation.streamId() != streamId) {
            emit(HpackDiagnosticReason.WRONG_STREAM_CONTINUATION,
                    continuation.streamId(),
                    "CONTINUATION belongs to a different stream");
            diagnostics.addAll(decoder.discardObservedContext(
                    "Incomplete field block was abandoned"));
            clearBlock();
            if (!continuation.endHeaders()) {
                discarding = true;
                streamId = continuation.streamId();
            }
            return result(HpackFrameAnalysisStatus.BLOCK_DISCARDED, null);
        }
        if (!append(continuation.headerBlockFragment())) {
            return discardOversized(continuation.streamId(), continuation.endHeaders());
        }
        return continuation.endHeaders()
                ? complete() : result(HpackFrameAnalysisStatus.AWAITING_CONTINUATION, null);
    }

    private HpackFrameAnalysis acceptIdle(Http2Frame frame) {
        if (frame instanceof ContinuationFrame continuation) {
            emit(HpackDiagnosticReason.UNEXPECTED_CONTINUATION,
                    continuation.streamId(), "CONTINUATION has no observed field-block start");
            diagnostics.addAll(decoder.discardObservedContext(
                    "Unobserved field-block bytes were discarded"));
            if (!continuation.endHeaders()) {
                discarding = true;
                streamId = continuation.streamId();
            }
            return result(HpackFrameAnalysisStatus.BLOCK_DISCARDED, null);
        }
        if (frame instanceof HeadersFrame headers) {
            begin(HeaderBlockOrigin.HEADERS, headers.streamId(), headers.endStream(), 0);
            if (!append(headers.headerBlockFragment())) {
                return discardOversized(headers.streamId(), headers.endHeaders());
            }
            return headers.endHeaders()
                    ? complete() : result(HpackFrameAnalysisStatus.AWAITING_CONTINUATION, null);
        }
        if (frame instanceof PushPromiseFrame pushPromise) {
            begin(HeaderBlockOrigin.PUSH_PROMISE, pushPromise.streamId(), false,
                    pushPromise.promisedStreamId());
            if (!append(pushPromise.headerBlockFragment())) {
                return discardOversized(pushPromise.streamId(),
                        pushPromise.endHeaders());
            }
            return pushPromise.endHeaders()
                    ? complete() : result(HpackFrameAnalysisStatus.AWAITING_CONTINUATION, null);
        }
        return result(HpackFrameAnalysisStatus.IGNORED, null);
    }

    private void begin(HeaderBlockOrigin value, int valueStreamId,
                       boolean valueEndStream, int valuePromisedStreamId) {
        origin = value;
        streamId = valueStreamId;
        endStream = valueEndStream;
        promisedStreamId = valuePromisedStreamId;
        active = true;
    }

    private boolean append(ByteSequence fragment) {
        if ((long) fragment.length() + encodedLength
                > decoder.config().maxEncodedHeaderBlockSize()) {
            return false;
        }
        if (!fragment.isEmpty()) {
            fragments.add(fragment);
        }
        encodedLength += fragment.length();
        return true;
    }

    private HpackFrameAnalysis discardOversized(int discardedStreamId,
                                                 boolean endHeaders) {
        emit(HpackDiagnosticReason.RESOURCE_LIMIT, discardedStreamId,
                "Encoded field block exceeds configured limit");
        diagnostics.addAll(decoder.discardObservedContext(
                "Oversized field block was not decoded"));
        clearBlock();
        if (!endHeaders) {
            discarding = true;
            streamId = discardedStreamId;
        }
        return result(HpackFrameAnalysisStatus.BLOCK_DISCARDED, null);
    }

    private HpackFrameAnalysis complete() {
        int completedStreamId = streamId;
        try {
            HpackBlockAnalysis analysis = decoder.analyzeFragments(fragments,
                    encodedLength, completedStreamId);
            diagnostics.addAll(analysis.diagnostics());
            DecodedHeaderBlock block = new DecodedHeaderBlock(origin, completedStreamId,
                    endStream, origin == HeaderBlockOrigin.PUSH_PROMISE
                            ? OptionalInt.of(promisedStreamId) : OptionalInt.empty(),
                    analysis.fields(), analysis.status(), analysis.omittedFieldCount(),
                    analysis.contextCompleteness());
            return result(HpackFrameAnalysisStatus.BLOCK_ANALYZED, block);
        } finally {
            clearBlock();
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
        discarding = false;
    }

    private void emit(HpackDiagnosticReason reason, int diagnosticStreamId,
                      String message) {
        diagnostics.add(new HpackDiagnostic(reason, -1, -1,
                diagnosticStreamId, message));
    }

    private HpackFrameAnalysis result(HpackFrameAnalysisStatus status,
                                      DecodedHeaderBlock block) {
        return new HpackFrameAnalysis(status, Optional.ofNullable(block),
                decoder.contextCompleteness(), diagnostics);
    }

    private static void validateRestoredAssembler(HpackFrameAssemblerSnapshot snapshot,
                                                   HeaderBlockOrigin restoredOrigin,
                                                   int restoredPromisedStream)
            throws HpackSnapshotException {
        if (restoredOrigin == null || snapshot.streamId() <= 0) {
            throw invalidAssemblerSnapshot("Active snapshot has invalid metadata");
        }
        if (restoredOrigin == HeaderBlockOrigin.HEADERS && restoredPromisedStream != 0) {
            throw invalidAssemblerSnapshot("HEADERS snapshot has a promised stream ID");
        }
        if (restoredOrigin == HeaderBlockOrigin.PUSH_PROMISE
                && (restoredPromisedStream <= 0 || snapshot.endStream())) {
            throw invalidAssemblerSnapshot("Invalid PUSH_PROMISE snapshot metadata");
        }
    }

    private static HpackSnapshotException invalidAssemblerSnapshot(String message) {
        return new HpackSnapshotException(HpackSnapshotErrorReason.INVALID_ASSEMBLER_STATE,
                -1, message);
    }
}
