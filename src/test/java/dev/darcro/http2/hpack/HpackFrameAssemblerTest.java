package dev.darcro.http2.hpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.darcro.http2.frame.ByteSequence;
import dev.darcro.http2.frame.ContinuationFrame;
import dev.darcro.http2.frame.DataFrame;
import dev.darcro.http2.frame.HeadersFrame;
import dev.darcro.http2.frame.Http2Flags;
import dev.darcro.http2.frame.Http2FrameParser;
import dev.darcro.http2.frame.PushPromiseFrame;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class HpackFrameAssemblerTest {
    @Test
    void analyzesSingleHeadersFrameWithMetadata() throws Exception {
        HpackFrameAssembler assembler = HpackFrameAssembler.atConnectionStart();
        HpackFrameAnalysis analysis = assembler.accept(headers(1,
                Http2Flags.END_HEADERS | Http2Flags.END_STREAM, "828684"));

        assertEquals(HpackFrameAnalysisStatus.BLOCK_ANALYZED, analysis.status());
        DecodedHeaderBlock block = analysis.decodedBlock().orElseThrow();
        assertEquals(HeaderBlockOrigin.HEADERS, block.origin());
        assertEquals(1, block.streamId());
        assertTrue(block.endStream());
        assertEquals("GET", block.method().orElseThrow());
        assertEquals(HpackContextCompleteness.OBSERVED_COMPLETE,
                block.contextCompleteness());
    }

    @Test
    void acceptsFramesProducedByWireParser() throws Exception {
        HpackFrameAssembler assembler = HpackFrameAssembler.atConnectionStart();
        HpackFrameAnalysis result = assembler.accept(new Http2FrameParser().parse(
                hex("000003010500000001828684")));
        assertEquals("GET", result.decodedBlock().orElseThrow().method().orElseThrow());
    }

    @Test
    void reassemblesContinuationFragments() {
        HpackFrameAssembler assembler = HpackFrameAssembler.atConnectionStart();
        assertEquals(HpackFrameAnalysisStatus.AWAITING_CONTINUATION,
                assembler.accept(headers(3, 0, "8286")).status());

        HpackFrameAnalysis result = assembler.accept(continuation(3,
                Http2Flags.END_HEADERS,
                hex("84410f7777772e6578616d706c652e636f6d")));

        assertEquals(4, result.decodedBlock().orElseThrow().fields().size());
    }

    @Test
    void preservesPushPromiseMetadata() {
        HpackFrameAssembler assembler = HpackFrameAssembler.atConnectionStart();
        ByteSequence fragment = sequence(hex("8286"));
        PushPromiseFrame frame = new PushPromiseFrame(fragment.length(),
                Http2Flags.END_HEADERS, 1, 2, fragment, 0);

        DecodedHeaderBlock block = assembler.accept(frame).decodedBlock().orElseThrow();
        assertEquals(HeaderBlockOrigin.PUSH_PROMISE, block.origin());
        assertEquals(2, block.promisedStreamId().orElseThrow());
    }

    @Test
    void ignoresNonHeaderFramesWhileIdle() {
        HpackFrameAnalysis result = new HpackFrameAssembler().accept(
                new DataFrame(0, 0, 1, sequence(new byte[0]), 0));
        assertEquals(HpackFrameAnalysisStatus.IGNORED, result.status());
    }

    @Test
    void recoversOrphanContinuationAndContinues() {
        List<HpackDiagnostic> diagnostics = new ArrayList<>();
        HpackFrameAssembler assembler = new HpackFrameAssembler(
                HpackDecoderConfig.defaults(), diagnostics::add);

        HpackFrameAnalysis orphan = assembler.accept(continuation(1,
                Http2Flags.END_HEADERS, hex("82")));
        HpackFrameAnalysis later = assembler.accept(headers(1,
                Http2Flags.END_HEADERS, "82"));

        assertEquals(HpackFrameAnalysisStatus.BLOCK_DISCARDED, orphan.status());
        assertEquals("GET", later.decodedBlock().orElseThrow().method().orElseThrow());
        assertTrue(diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.reason() == HpackDiagnosticReason.UNEXPECTED_CONTINUATION));
    }

    @Test
    void wrongStreamContinuationAbandonsCurrentBlock() {
        List<HpackDiagnostic> diagnostics = new ArrayList<>();
        HpackFrameAssembler assembler = HpackFrameAssembler.atConnectionStart(
                HpackDecoderConfig.defaults(), diagnostics::add);
        assembler.accept(headers(1, 0, "82"));

        HpackFrameAnalysis result = assembler.accept(continuation(3,
                Http2Flags.END_HEADERS, hex("86")));

        assertEquals(HpackFrameAnalysisStatus.BLOCK_DISCARDED, result.status());
        assertEquals(HpackContextCompleteness.PARTIAL, result.contextCompleteness());
        assertTrue(diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.reason() == HpackDiagnosticReason.WRONG_STREAM_CONTINUATION));
    }

    @Test
    void interleavedHeadersStartsNewBlockAfterDiscardingOld() {
        HpackFrameAssembler assembler = HpackFrameAssembler.atConnectionStart();
        assembler.accept(headers(1, 0, "86"));

        HpackFrameAnalysis result = assembler.accept(headers(3,
                Http2Flags.END_HEADERS, "82"));

        assertEquals(3, result.decodedBlock().orElseThrow().streamId());
        assertEquals(HpackContextCompleteness.PARTIAL, result.contextCompleteness());
    }

    @Test
    void malformedHpackReturnsPartialBlockAndLaterBlocksStillWork() {
        HpackFrameAssembler assembler = HpackFrameAssembler.atConnectionStart();

        DecodedHeaderBlock malformed = assembler.accept(headers(1,
                Http2Flags.END_HEADERS, "8280")).decodedBlock().orElseThrow();
        DecodedHeaderBlock later = assembler.accept(headers(3,
                Http2Flags.END_HEADERS, "82")).decodedBlock().orElseThrow();

        assertEquals(HpackBlockStatus.INCOMPLETE, malformed.analysisStatus());
        assertEquals(1, malformed.fields().size());
        assertEquals("GET", later.method().orElseThrow());
    }

    @Test
    void oversizedFragmentedBlockIsDiscardedThroughEndHeaders() {
        HpackFrameAssembler assembler = new HpackFrameAssembler(
                new HpackDecoderConfig(4096, 2, 1024));

        assertEquals(HpackFrameAnalysisStatus.AWAITING_CONTINUATION,
                assembler.accept(headers(1, 0, "82")).status());
        assertEquals(HpackFrameAnalysisStatus.BLOCK_DISCARDED,
                assembler.accept(continuation(1, 0, hex("8684"))).status());
        assertTrue(assembler.snapshot().discarding());
        assertEquals(HpackFrameAnalysisStatus.BLOCK_DISCARDED,
                assembler.accept(continuation(1, Http2Flags.END_HEADERS,
                        new byte[0])).status());
        assertFalse(assembler.snapshot().discarding());

        assertEquals("GET", assembler.accept(headers(3, Http2Flags.END_HEADERS,
                "82")).decodedBlock().orElseThrow().method().orElseThrow());
    }

    @Test
    void wrongStreamWhileDiscardingEmitsOneDiagnosticAndTracksNewBlock() {
        List<HpackDiagnostic> diagnostics = new ArrayList<>();
        HpackFrameAssembler assembler = new HpackFrameAssembler(
                new HpackDecoderConfig(4096, 1, 1024), diagnostics::add);
        assembler.accept(headers(1, 0, "8286"));
        diagnostics.clear();

        HpackFrameAnalysis result = assembler.accept(continuation(3, 0, hex("82")));

        assertEquals(HpackFrameAnalysisStatus.BLOCK_DISCARDED, result.status());
        assertEquals(1, diagnostics.size());
        assertEquals(HpackDiagnosticReason.WRONG_STREAM_CONTINUATION,
                diagnostics.get(0).reason());
        assertEquals(3, assembler.snapshot().streamId());
        assertTrue(assembler.snapshot().discarding());
    }

    @Test
    void emptyContinuationsAreNotRetained() {
        HpackFrameAssembler assembler = new HpackFrameAssembler();
        assembler.accept(headers(1, 0, ""));
        for (int i = 0; i < 1000; i++) {
            assembler.accept(continuation(1, 0, new byte[0]));
        }
        assertTrue(assembler.snapshot().incompleteBlock().isEmpty());
        assertEquals(HpackFrameAnalysisStatus.BLOCK_ANALYZED,
                assembler.accept(continuation(1, Http2Flags.END_HEADERS,
                        new byte[0])).status());
    }

    @Test
    void listenerFailureDoesNotLeaveTrustedContext() {
        HpackFrameAssembler assembler = HpackFrameAssembler.atConnectionStart(
                HpackDecoderConfig.defaults(), diagnostic -> {
                    throw new IllegalStateException("sink failed");
                });

        assertThrows(IllegalStateException.class, () -> assembler.accept(
                continuation(1, Http2Flags.END_HEADERS, hex("82"))));
        assertEquals(HpackContextCompleteness.PARTIAL,
                assembler.contextCompleteness());
    }

    @Test
    void assemblerDoesNotExposeOwnedDecoder() {
        assertThrows(NoSuchMethodException.class,
                () -> HpackFrameAssembler.class.getConstructor(HpackDecoder.class));
        assertThrows(NoSuchMethodException.class,
                () -> HpackFrameAssembler.class.getMethod("decoder"));
    }

    private static HeadersFrame headers(int streamId, int flags, String value) {
        ByteSequence fragment = sequence(hex(value));
        return new HeadersFrame(fragment.length(), flags, streamId, fragment, null, 0);
    }

    private static ContinuationFrame continuation(int streamId, int flags, byte[] bytes) {
        return new ContinuationFrame(bytes.length, flags, streamId, sequence(bytes));
    }

    private static ByteSequence sequence(byte[] bytes) {
        return ByteSequence.wrap(bytes);
    }

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }
}
