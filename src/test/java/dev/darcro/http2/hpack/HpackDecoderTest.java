package dev.darcro.http2.hpack;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;

class HpackDecoderTest {
    @Test
    void decodesRfcAppendixCRequestsWithoutHuffmanCoding() throws Exception {
        HpackDecoder decoder = new HpackDecoder();

        assertFields(decoder.decode(hex(
                        "828684410f7777772e6578616d706c652e636f6d")),
                ":method", "GET", ":scheme", "http", ":path", "/",
                ":authority", "www.example.com");
        assertFields(decoder.decode(hex("828684be58086e6f2d6361636865")),
                ":method", "GET", ":scheme", "http", ":path", "/",
                ":authority", "www.example.com", "cache-control", "no-cache");
        assertFields(decoder.decode(hex(
                        "828785bf400a637573746f6d2d6b65790c637573746f6d2d76616c7565")),
                ":method", "GET", ":scheme", "https", ":path", "/index.html",
                ":authority", "www.example.com", "custom-key", "custom-value");
        assertEquals(164, decoder.dynamicTableSize());
    }

    @Test
    void decodesRfcAppendixCRequestsWithHuffmanCoding() throws Exception {
        HpackDecoder decoder = new HpackDecoder();

        assertFields(decoder.decode(hex("828684418cf1e3c2e5f23a6ba0ab90f4ff")),
                ":method", "GET", ":scheme", "http", ":path", "/",
                ":authority", "www.example.com");
        assertFields(decoder.decode(hex("828684be5886a8eb10649cbf")),
                ":method", "GET", ":scheme", "http", ":path", "/",
                ":authority", "www.example.com", "cache-control", "no-cache");
        assertFields(decoder.decode(hex(
                        "828785bf408825a849e95ba97d7f8925a849e95bb8e8b4bf")),
                ":method", "GET", ":scheme", "https", ":path", "/index.html",
                ":authority", "www.example.com", "custom-key", "custom-value");
    }

    @Test
    void decodesRfcAppendixCResponsesWithoutHuffmanCoding() throws Exception {
        HpackDecoder decoder = new HpackDecoder();

        assertResponseFields(decoder.decode(hex(
                "4803333032580770726976617465611d4d6f6e2c203231204f63742032303133"
                        + "2032303a31333a323120474d546e1768747470733a2f2f7777772e6578616d70"
                        + "6c652e636f6d")), "302", "21");
        assertResponseFields(decoder.decode(hex("4803333037c1c0bf")), "307", "21");

        List<HpackHeaderField> third = decoder.decode(hex(
                "88c1611d4d6f6e2c203231204f637420323031332032303a31333a323220474d"
                        + "54c05a04677a69707738666f6f3d4153444a4b48514b425a584f5157454f5049"
                        + "5541585157454f49553b206d61782d6167653d333630303b2076657273696f6e"
                        + "3d31"));
        assertThirdResponse(third);
    }

    @Test
    void decodesRfcAppendixCResponsesWithHuffmanCoding() throws Exception {
        HpackDecoder decoder = new HpackDecoder();

        assertResponseFields(decoder.decode(hex(
                "488264025885aec3771a4b6196d07abe941054d444a8200595040b8166e082a62d"
                        + "1bff6e919d29ad171863c78f0b97c8e9ae82ae43d3")), "302", "21");
        assertResponseFields(decoder.decode(hex("4883640effc1c0bf")), "307", "21");

        List<HpackHeaderField> third = decoder.decode(hex(
                "88c16196d07abe941054d444a8200595040b8166e084a62d1bffc05a839bd9ab"
                        + "77ad94e7821dd7f2e6c7b335dfdfcd5b3960d5af27087f3672c1ab270fb5291f"
                        + "9587316065c003ed4ee5b1063d5007"));
        assertThirdResponse(third);
    }

    @Test
    void decodesCapturedNghttpxResponse() throws Exception {
        List<HpackHeaderField> fields = new HpackDecoder().decode(hex(
                "886196dd6d5f4a044a436cca08017940bb71905c682a62d1bf5f87497ca58ae8"
                        + "19aa6c96df697e9403ca681fa50400bca059b8db3704253168df0f138bfe5b1c"
                        + "a11c7209664bfcff52848fd24a8f0f0d023632408ff2b4632752d522d3947216"
                        + "c5ac4a7f8602e0009c69bf7686aa69d29afcff7c8712954d3a535f9f408bf2b"
                        + "4b60e92ac7ad263d48f89dd0e8c1ab6e4c5934f408cf2b794216aec3a4a4498"
                        + "f57f8a0fda949e42c11d07275f4090f2b10f524b52564faacab1eb498f523f85"
                        + "a8e8a8d2cb"));

        assertFields(fields,
                ":status", "200",
                "date", "Sun, 12 Aug 2018 17:30:41 GMT",
                "content-type", "text/plain",
                "last-modified", "Tue, 08 May 2018 13:53:22 GMT",
                "etag", "\"5af1abd2-3e\"",
                "accept-ranges", "bytes",
                "content-length", "62",
                "x-backend-header-rtt", "0.002645",
                "server", "nghttpx",
                "via", "2 nghttpx",
                "x-frame-options", "SAMEORIGIN",
                "x-xss-protection", "1; mode=block",
                "x-content-type-options", "nosniff");
    }

    @Test
    void preservesNeverIndexedMetadataAndDuplicateOrder() throws Exception {
        HpackDecoder decoder = new HpackDecoder();
        List<HpackHeaderField> fields = decoder.decode(hex(
                "1001610162000161016382"));

        assertEquals(3, fields.size());
        assertEquals("a", fields.get(0).nameUtf8());
        assertEquals("b", fields.get(0).valueUtf8());
        assertTrue(fields.get(0).sensitive());
        assertEquals("a", fields.get(1).nameUtf8());
        assertEquals("c", fields.get(1).valueUtf8());
        assertFalse(fields.get(1).sensitive());
        assertEquals(":method", fields.get(2).nameUtf8());
    }

    @Test
    void resolvesTheCompleteRfcStaticTableAddressSpace() throws Exception {
        byte[] indexes = new byte[61];
        for (int i = 0; i < indexes.length; i++) {
            indexes[i] = (byte) (0x80 | (i + 1));
        }

        List<HpackHeaderField> fields = new HpackDecoder().decode(indexes);
        assertEquals(61, fields.size());
        assertEquals(":authority", fields.get(0).nameUtf8());
        assertEquals(":method", fields.get(1).nameUtf8());
        assertEquals("GET", fields.get(1).valueUtf8());
        assertEquals("accept-encoding", fields.get(15).nameUtf8());
        assertEquals("gzip, deflate", fields.get(15).valueUtf8());
        assertEquals("www-authenticate", fields.get(60).nameUtf8());
    }

    @Test
    void decodedLiteralBytesAreIndependentOfTheInputBuffer() throws Exception {
        byte[] encoded = hex("0001610162");
        HpackHeaderField field = new HpackDecoder().decode(encoded).get(0);
        encoded[2] = 'z';
        encoded[4] = 'z';

        assertEquals("a", field.nameUtf8());
        assertEquals("b", field.valueUtf8());
    }

    @Test
    void appliesTableSizeUpdatesAndEvictsEntries() throws Exception {
        HpackDecoder decoder = new HpackDecoder();
        decoder.decode(hex("4001610162"));
        assertEquals(34, decoder.dynamicTableSize());

        decoder.updateMaxDynamicTableSize(0);
        assertTrue(decoder.decode(hex("20")).isEmpty());
        assertEquals(0, decoder.dynamicTableSize());
        assertEquals(0, decoder.maxDynamicTableSize());
    }

    @Test
    void handlesMultipleRequiredTableSizeChanges() throws Exception {
        HpackDecoderConfig config = new HpackDecoderConfig(8_192, 65_536, 65_536);
        HpackDecoder decoder = new HpackDecoder(config);
        decoder.updateMaxDynamicTableSize(128);
        decoder.updateMaxDynamicTableSize(256);

        assertTrue(decoder.decode(hex("3f21")).isEmpty());
        assertEquals(256, decoder.maxDynamicTableSize());
    }

    @Test
    void rejectsMalformedInputAndPoisonsDecoder() {
        HpackDecoder decoder = new HpackDecoder();
        HpackDecodingException invalidIndex = assertThrows(HpackDecodingException.class,
                () -> decoder.decode(hex("80")));
        assertEquals(HpackErrorReason.INVALID_INDEX, invalidIndex.reason());
        assertTrue(invalidIndex.streamId().isEmpty());
        assertTrue(decoder.failed());

        HpackDecodingException failed = assertThrows(HpackDecodingException.class,
                () -> decoder.decode(hex("82")));
        assertEquals(HpackErrorReason.DECODER_FAILED, failed.reason());
    }

    @Test
    void skipsMissingDynamicIndexedFieldsByDefault() throws Exception {
        HpackDecoder decoder = new HpackDecoder();

        HpackDecodeResult result = decoder.decodeResult(hex("be82"));

        assertTrue(result.recovered());
        assertEquals(1, result.fields().size());
        assertEquals(":method", result.fields().get(0).nameUtf8());
        assertEquals("GET", result.fields().get(0).valueUtf8());
        assertEquals(1, result.recoveryEvents().size());
        HpackRecoveryEvent event = result.recoveryEvents().get(0);
        assertEquals(HpackRecoveryReason.MISSING_DYNAMIC_TABLE_INDEX, event.reason());
        assertEquals(0, event.offset());
        assertEquals(62, event.index());

        assertFields(decoder.decode(hex("82")), ":method", "GET");
        assertFalse(decoder.failed());
    }

    @Test
    void skipsLiteralWithMissingDynamicNameAndKeepsBlockAlignment() throws Exception {
        HpackDecoder decoder = new HpackDecoder();

        HpackDecodeResult result = decoder.decodeResult(hex("7e017884"));

        assertTrue(result.recovered());
        assertEquals(1, result.fields().size());
        assertEquals(":path", result.fields().get(0).nameUtf8());
        assertEquals("/", result.fields().get(0).valueUtf8());
        assertEquals(1, result.recoveryEvents().size());
        HpackRecoveryEvent event = result.recoveryEvents().get(0);
        assertEquals(HpackRecoveryReason.MISSING_DYNAMIC_TABLE_NAME_INDEX,
                event.reason());
        assertEquals(0, event.offset());
        assertEquals(62, event.index());
        assertEquals(0, decoder.dynamicTableSize());
        assertFalse(decoder.failed());
    }

    @Test
    void canFailOnMissingDynamicIndexesForStrictConnections() {
        HpackDecoder decoder = new HpackDecoder(new HpackDecoderConfig(
                4096, 1024, 1024,
                HpackDynamicTableRecoveryPolicy.FAIL_ON_MISSING));

        HpackDecodingException invalidIndex = assertThrows(HpackDecodingException.class,
                () -> decoder.decode(hex("be")));

        assertEquals(HpackErrorReason.INVALID_INDEX, invalidIndex.reason());
        assertTrue(decoder.failed());
        HpackDecodingException failed = assertThrows(HpackDecodingException.class,
                () -> decoder.decode(hex("82")));
        assertEquals(HpackErrorReason.DECODER_FAILED, failed.reason());
    }

    @Test
    void rejectsTruncationIntegerOverflowAndInvalidHuffman() {
        assertError(HpackErrorReason.TRUNCATED_INPUT, "400561");
        assertError(HpackErrorReason.INTEGER_OVERFLOW, "3fffffffffff7f");
        assertError(HpackErrorReason.INVALID_HUFFMAN, "0081ff00");
        assertError(HpackErrorReason.INVALID_HUFFMAN, "0084ffffffff00");
    }

    @Test
    void enforcesConfiguredBlockAndHeaderListLimits() {
        HpackDecoder blockDecoder = new HpackDecoder(
                new HpackDecoderConfig(4096, 2, 1024));
        HpackDecodingException blockError = assertThrows(HpackDecodingException.class,
                () -> blockDecoder.decode(hex("828684")));
        assertEquals(HpackErrorReason.ENCODED_BLOCK_TOO_LARGE, blockError.reason());

        HpackDecoder listDecoder = new HpackDecoder(
                new HpackDecoderConfig(4096, 1024, 33));
        HpackDecodingException listError = assertThrows(HpackDecodingException.class,
                () -> listDecoder.decode(hex("82")));
        assertEquals(HpackErrorReason.HEADER_LIST_TOO_LARGE, listError.reason());
    }

    @Test
    void rejectsMissingMisplacedAndExcessiveTableUpdates() throws Exception {
        HpackDecoder missing = new HpackDecoder();
        missing.updateMaxDynamicTableSize(0);
        assertEquals(HpackErrorReason.INVALID_TABLE_SIZE_UPDATE,
                assertThrows(HpackDecodingException.class,
                        () -> missing.decode(hex("82"))).reason());

        assertError(HpackErrorReason.INVALID_TABLE_SIZE_UPDATE, "8220");
        assertError(HpackErrorReason.INVALID_TABLE_SIZE_UPDATE, "202020");
        assertError(HpackErrorReason.INVALID_TABLE_SIZE_UPDATE, "3fe21f");
    }

    @Test
    void validatesConfigurationAndRanges() {
        assertSame(HpackDecoderConfig.DEFAULT_CONFIG, HpackDecoderConfig.defaults());
        assertSame(HpackDecoderConfig.DEFAULT_CONFIG, new HpackDecoder().config());
        assertEquals(HpackDynamicTableRecoveryPolicy.SKIP_MISSING,
                new HpackDecoderConfig(4096, 10, 10).dynamicTableRecoveryPolicy());
        assertThrows(IllegalArgumentException.class,
                () -> new HpackDecoderConfig(4095, 10, 10));
        assertThrows(IllegalArgumentException.class,
                () -> new HpackDecoderConfig(4096, 0, 10));
        assertThrows(NullPointerException.class,
                () -> new HpackDecoderConfig(4096, 10, 10, null));
        assertThrows(IndexOutOfBoundsException.class,
                () -> new HpackDecoder().decode(new byte[2], 1, 2));
        assertThrows(IllegalArgumentException.class,
                () -> new HpackDecoder().updateMaxDynamicTableSize(4097));
    }

    private static void assertError(HpackErrorReason reason, String hex) {
        HpackDecodingException error = assertThrows(HpackDecodingException.class,
                () -> new HpackDecoder().decode(hex(hex)));
        assertEquals(reason, error.reason(), error.getMessage());
    }

    private static void assertFields(List<HpackHeaderField> fields, String... pairs) {
        assertEquals(pairs.length / 2, fields.size());
        for (int i = 0; i < fields.size(); i++) {
            assertEquals(pairs[i * 2], fields.get(i).nameUtf8(), "name at " + i);
            assertEquals(pairs[i * 2 + 1], fields.get(i).valueUtf8(), "value at " + i);
        }
    }

    private static void assertResponseFields(List<HpackHeaderField> fields,
                                             String status, String second) {
        assertFields(fields,
                ":status", status,
                "cache-control", "private",
                "date", "Mon, 21 Oct 2013 20:13:" + second + " GMT",
                "location", "https://www.example.com");
    }

    private static void assertThirdResponse(List<HpackHeaderField> fields) {
        assertFields(fields,
                ":status", "200",
                "cache-control", "private",
                "date", "Mon, 21 Oct 2013 20:13:22 GMT",
                "location", "https://www.example.com",
                "content-encoding", "gzip",
                "set-cookie", "foo=ASDJKHQKBZXOQWEOPIUAXQWEOIU; "
                        + "max-age=3600; version=1");
    }

    private static byte[] hex(String value) {
        return HexFormat.of().parseHex(value);
    }
}
