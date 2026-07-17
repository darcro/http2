package dev.darcro.http2.hpack;

import dev.darcro.http2.frame.ByteSequence;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalInt;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class HpackHeaderFieldsTest {
    @Test
    void cachesRequestAndResponsePseudoFieldValues() {
        DecodedHeaderBlock request = block(List.of(
                field(":method", "GET"),
                field(":scheme", "https"),
                field(":authority", "example.com"),
                field(":path", "/index.html"),
                field("accept", "text/plain")));

        assertEquals("GET", request.method().orElseThrow());
        assertEquals("https", request.scheme().orElseThrow());
        assertEquals("example.com", request.authority().orElseThrow());
        assertEquals("/index.html", request.path().orElseThrow());
        assertTrue(request.status().isEmpty());
        assertFalse(request.hasDuplicatePseudoHeaders());

        DecodedHeaderBlock response = block(List.of(field(":STATUS", "204")));
        assertEquals("204", response.status().orElseThrow());
        assertTrue(response.method().isEmpty());
    }

    @Test
    void reportsDuplicatePseudoFieldsAndKeepsTheFirstValue() {
        DecodedHeaderBlock block = block(List.of(
                field(":method", "GET"),
                field(":METHOD", "POST"),
                field(":path", "/")));

        assertEquals("GET", block.method().orElseThrow());
        assertTrue(block.hasDuplicatePseudoHeaders());
        assertEquals(2, block.headerFields().all(":method").size());
    }

    @Test
    void findsArbitraryFieldsCaseInsensitivelyAndPreservesDuplicates() {
        HpackHeaderField firstCookie = field("set-cookie", "a=1");
        HpackHeaderField secondCookie = field("set-cookie", "b=2");
        HpackHeaderFields fields = HpackHeaderFields.copyOf(List.of(
                field("content-type", "text/plain"), firstCookie, secondCookie));

        assertEquals("text/plain",
                fields.first("Content-Type").orElseThrow().valueUtf8());
        assertEquals(List.of(firstCookie, secondCookie), fields.all("SET-COOKIE"));
        assertTrue(fields.contains("content-TYPE"));
        assertFalse(fields.contains("missing"));
        assertTrue(fields.first("missing").isEmpty());
        assertTrue(fields.all("missing").isEmpty());
        assertFalse(fields.contains("contént-type"));
    }

    @Test
    void wrapsFieldsReturnedDirectlyByTheDecoder() throws Exception {
        HpackHeaderFields fields = new HpackDecoder().analyze(hex("828684")).fields();

        assertEquals("GET", fields.first(":method").orElseThrow().valueUtf8());
        assertTrue(fields.contains(":scheme"));
        assertEquals(3, fields.size());
    }

    @Test
    void createsAnImmutableListAndReusesAnExistingView() {
        List<HpackHeaderField> source = new ArrayList<>();
        source.add(field("a", "b"));
        HpackHeaderFields fields = HpackHeaderFields.copyOf(source);
        source.clear();

        assertEquals(1, fields.size());
        assertThrows(UnsupportedOperationException.class,
                () -> fields.add(field("c", "d")));
        assertThrows(UnsupportedOperationException.class,
                () -> fields.all("a").clear());
        assertSame(fields, HpackHeaderFields.copyOf(fields));

        DecodedHeaderBlock block = block(fields);
        assertSame(fields, block.headerFields());
        assertSame(fields, block.fields());
    }

    @Test
    void decodesAByteSequenceRangeWithoutChangingItsView() {
        byte[] bytes = "xhéy".getBytes(StandardCharsets.UTF_8);
        ByteSequence sequence = ByteSequence.wrap(bytes, 1, 3);

        assertEquals("hé", sequence.decode(StandardCharsets.UTF_8));
        assertEquals(3, sequence.length());
        assertEquals('h', sequence.unsignedByteAt(0));
    }

    private static DecodedHeaderBlock block(List<HpackHeaderField> fields) {
        return new DecodedHeaderBlock(HeaderBlockOrigin.HEADERS, 1, false,
                OptionalInt.empty(), fields, HpackBlockStatus.COMPLETE, 0,
                HpackContextCompleteness.OBSERVED_COMPLETE);
    }

    private static HpackHeaderField field(String name, String value) {
        return new HpackHeaderField(sequence(name), sequence(value), false);
    }

    private static ByteSequence sequence(String value) {
        return ByteSequence.wrap(value.getBytes(StandardCharsets.UTF_8));
    }

    private static byte[] hex(String value) {
        return java.util.HexFormat.of().parseHex(value);
    }
}
