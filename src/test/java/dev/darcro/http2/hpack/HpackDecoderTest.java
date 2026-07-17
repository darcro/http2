package dev.darcro.http2.hpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class HpackDecoderTest {
    @Test
    void decodesRfcAppendixCRequestsAndTracksDynamicState() {
        HpackDecoder decoder = HpackDecoder.atConnectionStart();

        assertFields(decoder.analyze(hex(
                        "828684410f7777772e6578616d706c652e636f6d")).fields(),
                ":method", "GET", ":scheme", "http", ":path", "/",
                ":authority", "www.example.com");
        HpackBlockAnalysis second = decoder.analyze(hex("828684be58086e6f2d6361636865"));
        assertFields(second.fields(),
                ":method", "GET", ":scheme", "http", ":path", "/",
                ":authority", "www.example.com", "cache-control", "no-cache");
        assertEquals(HpackValueSource.DYNAMIC_TABLE,
                second.fields().get(3).provenance().nameSource());
        decoder.analyze(hex(
                "828785bf400a637573746f6d2d6b65790c637573746f6d2d76616c7565"));
        assertEquals(164, decoder.dynamicTableSize());
    }

    @Test
    void decodesRfcHuffmanExamples() {
        HpackDecoder decoder = HpackDecoder.atConnectionStart();
        assertFields(decoder.analyze(hex("828684418cf1e3c2e5f23a6ba0ab90f4ff")).fields(),
                ":method", "GET", ":scheme", "http", ":path", "/",
                ":authority", "www.example.com");
        assertFields(decoder.analyze(hex("828684be5886a8eb10649cbf")).fields(),
                ":method", "GET", ":scheme", "http", ":path", "/",
                ":authority", "www.example.com", "cache-control", "no-cache");
    }

    @Test
    void decodesCapturedNghttpxResponse() {
        HpackBlockAnalysis result = HpackDecoder.atConnectionStart().analyze(hex(
                "886196dd6d5f4a044a436cca08017940bb71905c682a62d1bf5f87497ca58ae8"
                        + "19aa6c96df697e9403ca681fa50400bca059b8db3704253168df0f138bfe5b1c"
                        + "a11c7209664bfcff52848fd24a8f0f0d023632408ff2b4632752d522d3947216"
                        + "c5ac4a7f8602e0009c69bf7686aa69d29afcff7c8712954d3a535f9f408bf2b"
                        + "4b60e92ac7ad263d48f89dd0e8c1ab6e4c5934f408cf2b794216aec3a4a4498"
                        + "f57f8a0fda949e42c11d07275f4090f2b10f524b52564faacab1eb498f523f85"
                        + "a8e8a8d2cb"));

        assertTrue(result.complete());
        assertEquals("200", result.fields().first(":status").orElseThrow().valueUtf8());
        assertEquals("nghttpx", result.fields().first("server").orElseThrow().valueUtf8());
        assertEquals(13, result.fields().size());
    }

    @Test
    void recordsNameAndValueProvenance() {
        HpackDecoder decoder = HpackDecoder.atConnectionStart();
        HpackHeaderFields fields = decoder.analyze(hex("8240016101620f1d0178")).fields();

        assertEquals(HpackValueSource.STATIC_TABLE,
                fields.get(0).provenance().nameSource());
        assertEquals(HpackValueSource.STATIC_TABLE,
                fields.get(0).provenance().valueSource());
        assertEquals(2, fields.get(0).provenance().tableIndex());
        assertEquals(HpackFieldProvenance.literal(), fields.get(1).provenance());
        assertEquals(HpackValueSource.STATIC_TABLE,
                fields.get(2).provenance().nameSource());
        assertEquals(HpackValueSource.LITERAL,
                fields.get(2).provenance().valueSource());
        assertEquals(44, fields.get(2).provenance().tableIndex());
    }

    @Test
    void defaultsToPartialAndReturnsLocallyResolvableDynamicFields() {
        HpackDecoder decoder = new HpackDecoder();
        decoder.analyze(hex("4001610162"));

        HpackBlockAnalysis result = decoder.analyze(hex("be"));

        assertEquals(HpackContextCompleteness.PARTIAL, result.contextCompleteness());
        assertEquals("a", result.fields().get(0).nameUtf8());
        assertEquals(HpackValueSource.DYNAMIC_TABLE,
                result.fields().get(0).provenance().nameSource());
    }

    @Test
    void skipsUnavailableIndexesAndEmitsDiagnostics() {
        List<HpackDiagnostic> diagnostics = new ArrayList<>();
        HpackDecoder decoder = new HpackDecoder(HpackDecoderConfig.defaults(),
                diagnostics::add);

        HpackBlockAnalysis result = decoder.analyze(hex("be82"));

        assertTrue(result.complete());
        assertEquals(1, result.omittedFieldCount());
        assertEquals("GET", result.fields().get(0).valueUtf8());
        assertEquals(HpackDiagnosticReason.MISSING_DYNAMIC_TABLE_INDEX,
                diagnostics.get(0).reason());
        assertEquals(62, diagnostics.get(0).index());
    }

    @Test
    void unknownIncrementalNameClearsPotentiallyMisalignedTable() {
        List<HpackDiagnostic> diagnostics = new ArrayList<>();
        HpackDecoder decoder = new HpackDecoder(HpackDecoderConfig.defaults(),
                diagnostics::add);
        decoder.analyze(hex("4001610162"));
        assertEquals(34, decoder.dynamicTableSize());

        HpackBlockAnalysis missingName = decoder.analyze(hex("7f000178"));
        HpackBlockAnalysis laterIndex = decoder.analyze(hex("be82"));

        assertEquals(1, missingName.omittedFieldCount());
        assertEquals(0, decoder.dynamicTableSize());
        assertEquals(1, laterIndex.omittedFieldCount());
        assertEquals("GET", laterIndex.fields().get(0).valueUtf8());
        assertTrue(diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.reason() == HpackDiagnosticReason.CONTEXT_BECAME_PARTIAL));
    }

    @Test
    void omittedNamedLiteralsStillConsumeHeaderListBudget() {
        List<HpackDiagnostic> diagnostics = new ArrayList<>();
        HpackDecoder decoder = new HpackDecoder(
                new HpackDecoderConfig(4096, 1024, 40), diagnostics::add);

        HpackBlockAnalysis result = decoder.analyze(hex(
                "0f2f0561626364650f2f056162636465"));

        assertEquals(HpackBlockStatus.INCOMPLETE, result.status());
        assertEquals(1, result.omittedFieldCount());
        assertTrue(diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.reason() == HpackDiagnosticReason.RESOURCE_LIMIT));
    }

    @Test
    void malformedBlockReturnsPartialResultAndDecoderRemainsUsable() {
        List<HpackDiagnostic> diagnostics = new ArrayList<>();
        HpackDecoder decoder = HpackDecoder.atConnectionStart(
                HpackDecoderConfig.defaults(), diagnostics::add);

        HpackBlockAnalysis malformed = decoder.analyze(hex("8280"));
        HpackBlockAnalysis later = decoder.analyze(hex("82"));

        assertEquals(HpackBlockStatus.INCOMPLETE, malformed.status());
        assertEquals(1, malformed.fields().size());
        assertEquals(HpackContextCompleteness.PARTIAL, malformed.contextCompleteness());
        assertTrue(later.complete());
        assertEquals("GET", later.fields().get(0).valueUtf8());
        assertTrue(diagnostics.stream().anyMatch(diagnostic ->
                diagnostic.reason() == HpackDiagnosticReason.MALFORMED_BLOCK));
    }

    @Test
    void resourceFailureReturnsIncompleteAndRemainsUsable() {
        HpackDecoder decoder = new HpackDecoder(new HpackDecoderConfig(4096, 2, 1024));

        assertFalse(decoder.analyze(hex("828684")).complete());
        assertEquals("GET", decoder.analyze(hex("82")).fields().get(0).valueUtf8());
    }

    @Test
    void tableClearRestoresObservedCompleteness() {
        HpackDecoder decoder = new HpackDecoder();
        assertEquals(HpackContextCompleteness.PARTIAL, decoder.contextCompleteness());

        HpackBlockAnalysis result = decoder.analyze(hex("20"));

        assertEquals(HpackContextCompleteness.OBSERVED_COMPLETE,
                result.contextCompleteness());
        assertTrue(decoder.tableLimitKnown());
        assertEquals(0, decoder.dynamicTableSize());
    }

    @Test
    void appliesSettingsReductionAndRequiredUpdate() {
        HpackDecoder decoder = HpackDecoder.atConnectionStart();
        decoder.updateMaxDynamicTableSize(0);
        assertTrue(decoder.analyze(hex("20")).complete());
        assertEquals(0, decoder.maxDynamicTableSize());
    }

    @Test
    void rejectsMalformedHuffmanAndIntegerWithoutThrowing() {
        assertFalse(HpackDecoder.atConnectionStart().analyze(hex("0081ff00")).complete());
        assertFalse(HpackDecoder.atConnectionStart().analyze(hex("3fffffffffff7f"))
                .complete());
    }

    @Test
    void listenerFailureLeavesContextPartialAndRethrowsCallerFailure() {
        HpackDecoder decoder = HpackDecoder.atConnectionStart(
                HpackDecoderConfig.defaults(), diagnostic -> {
                    throw new IllegalStateException("sink failed");
                });

        assertThrows(IllegalStateException.class, () -> decoder.analyze(hex("80")));
        assertEquals(HpackContextCompleteness.PARTIAL, decoder.contextCompleteness());
        assertEquals(0, decoder.dynamicTableSize());
    }

    @Test
    void validatesConfigurationAndRanges() {
        assertSame(HpackDecoderConfig.DEFAULT_CONFIG, HpackDecoderConfig.defaults());
        assertThrows(IllegalArgumentException.class,
                () -> new HpackDecoderConfig(4095, 10, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new HpackDecoderConfig(4096, 0, 10));
        assertThrows(IndexOutOfBoundsException.class,
                () -> new HpackDecoder().analyze(new byte[2], 1, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new HpackDecoder().updateMaxDynamicTableSize(
                        HpackDecoderConfig.DEFAULT_DYNAMIC_TABLE_CAPACITY + 1));
    }

    private static void assertFields(List<HpackHeaderField> fields, String... pairs) {
        assertEquals(pairs.length / 2, fields.size());
        for (int i = 0; i < fields.size(); i++) {
            assertEquals(pairs[i * 2], fields.get(i).nameUtf8(), "name at " + i);
            assertEquals(pairs[i * 2 + 1], fields.get(i).valueUtf8(), "value at " + i);
        }
    }

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }
}
