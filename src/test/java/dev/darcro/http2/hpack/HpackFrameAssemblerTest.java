package dev.darcro.http2.hpack;

import dev.darcro.http2.frame.ByteSequence;
import dev.darcro.http2.frame.ContinuationFrame;
import dev.darcro.http2.frame.DataFrame;
import dev.darcro.http2.frame.HeadersFrame;
import dev.darcro.http2.frame.Http2Flags;
import dev.darcro.http2.frame.Http2Frame;
import dev.darcro.http2.frame.Http2FrameParser;
import dev.darcro.http2.frame.PushPromiseFrame;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HexFormat;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class HpackFrameAssemblerTest {
    @Test
    void decodesSingleHeadersFrameWithOriginMetadata() throws Exception {
        HpackFrameAssembler assembler = new HpackFrameAssembler();
        HeadersFrame frame = headers(1, Http2Flags.END_HEADERS | Http2Flags.END_STREAM,
                "828684");

        DecodedHeaderBlock block = assembler.accept(frame).orElseThrow();
        assertEquals(HeaderBlockOrigin.HEADERS, block.origin());
        assertEquals(1, block.streamId());
        assertTrue(block.endStream());
        assertTrue(block.promisedStreamId().isEmpty());
        assertEquals(3, block.fields().size());
    }

    @Test
    void acceptsAHeadersFrameProducedByTheExistingWireParser() throws Exception {
        Http2Frame parsed = new Http2FrameParser().parse(
                hex("000003010500000001828684"));
        HpackFrameAssembler assembler = new HpackFrameAssembler();

        DecodedHeaderBlock block = assembler.accept(parsed).orElseThrow();
        assertEquals(1, block.streamId());
        assertTrue(block.endStream());
        assertEquals(":method", block.fields().get(0).nameUtf8());
    }

    @Test
    void reassemblesContinuationFragmentsWithoutConcatenating() throws Exception {
        HpackFrameAssembler assembler = new HpackFrameAssembler();

        assertTrue(assembler.accept(headers(3, 0, "8286")).isEmpty());
        byte[] finalBytes = hex("84410f7777772e6578616d706c652e636f6d");
        ContinuationFrame continuation = continuation(3, Http2Flags.END_HEADERS,
                finalBytes);
        Optional<DecodedHeaderBlock> completed = assembler.accept(continuation);

        finalBytes[0] = 0;
        DecodedHeaderBlock block = completed.orElseThrow();
        assertEquals(4, block.fields().size());
        assertEquals("www.example.com", block.fields().get(3).valueUtf8());
    }

    @Test
    void decodesPushPromiseMetadata() throws Exception {
        HpackFrameAssembler assembler = new HpackFrameAssembler();
        ByteSequence fragment = sequence(hex("8286"));
        PushPromiseFrame frame = new PushPromiseFrame(fragment.length(),
                Http2Flags.END_HEADERS, 1, 2, fragment, 0);

        DecodedHeaderBlock block = assembler.accept(frame).orElseThrow();
        assertEquals(HeaderBlockOrigin.PUSH_PROMISE, block.origin());
        assertFalse(block.endStream());
        assertEquals(2, block.promisedStreamId().orElseThrow());
    }

    @Test
    void ignoresUnrelatedFramesWhileIdle() throws Exception {
        HpackFrameAssembler assembler = new HpackFrameAssembler();
        DataFrame data = new DataFrame(0, 0, 1, sequence(new byte[0]), 0);
        assertTrue(assembler.accept(data).isEmpty());
        assertFalse(assembler.failed());
    }

    @Test
    void rejectsUnexpectedWrongStreamAndInterleavedFrames() throws Exception {
        HpackFrameAssembler unexpected = new HpackFrameAssembler();
        HpackFrameSequenceException noOrigin = assertThrows(HpackFrameSequenceException.class,
                () -> unexpected.accept(continuation(1, Http2Flags.END_HEADERS,
                        hex("82"))));
        assertEquals(HpackFrameSequenceReason.UNEXPECTED_CONTINUATION, noOrigin.reason());
        assertTrue(unexpected.failed());

        HpackFrameAssembler wrongStream = new HpackFrameAssembler();
        wrongStream.accept(headers(1, 0, "82"));
        assertEquals(HpackFrameSequenceReason.WRONG_STREAM,
                assertThrows(HpackFrameSequenceException.class,
                        () -> wrongStream.accept(continuation(3,
                                Http2Flags.END_HEADERS, hex("86")))).reason());

        HpackFrameAssembler interleaved = new HpackFrameAssembler();
        interleaved.accept(headers(1, 0, "82"));
        DataFrame data = new DataFrame(0, 0, 1, sequence(new byte[0]), 0);
        assertEquals(HpackFrameSequenceReason.INTERLEAVED_FRAME,
                assertThrows(HpackFrameSequenceException.class,
                        () -> interleaved.accept(data)).reason());
    }

    @Test
    void propagatesDecodeFailureAndPoisonsAssembler() {
        HpackFrameAssembler assembler = new HpackFrameAssembler();
        assertThrows(HpackDecodingException.class,
                () -> assembler.accept(headers(1, Http2Flags.END_HEADERS, "80")));
        assertTrue(assembler.failed());
        assertEquals(HpackFrameSequenceReason.ASSEMBLER_FAILED,
                assertThrows(HpackFrameSequenceException.class,
                        () -> assembler.accept(headers(1, Http2Flags.END_HEADERS,
                                "82"))).reason());
    }

    @Test
    void rejectsAnOversizedBlockBeforeMoreFragmentsAreRetained() {
        HpackDecoderConfig config = new HpackDecoderConfig(4096, 2, 1024);
        HpackFrameAssembler assembler = new HpackFrameAssembler(config);

        HpackDecodingException error = assertThrows(HpackDecodingException.class,
                () -> assembler.accept(headers(1, 0, "828684")));
        assertEquals(HpackErrorReason.ENCODED_BLOCK_TOO_LARGE, error.reason());
        assertEquals(1, error.streamId().orElseThrow());
        assertTrue(assembler.failed());
    }

    @Test
    void exposesConfigurationAndSafeDynamicTableControls() throws Exception {
        HpackDecoderConfig config = new HpackDecoderConfig(8_192, 1_024, 2_048);
        HpackFrameAssembler assembler = new HpackFrameAssembler(config);

        assertEquals(HpackDecoderConfig.defaults(), new HpackFrameAssembler().config());
        assertEquals(config, assembler.config());
        assertEquals(0, assembler.dynamicTableSize());
        assertEquals(4_096, assembler.maxDynamicTableSize());
        assertThrows(IllegalArgumentException.class,
                () -> assembler.updateMaxDynamicTableSize(8_193));

        assembler.updateMaxDynamicTableSize(0);
        assertEquals(0, assembler.maxDynamicTableSize());
        DecodedHeaderBlock block = assembler.accept(
                headers(1, Http2Flags.END_HEADERS, "20")).orElseThrow();
        assertTrue(block.fields().isEmpty());
        assertEquals(0, assembler.dynamicTableSize());

        assertThrows(HpackFrameSequenceException.class,
                () -> assembler.accept(continuation(1, Http2Flags.END_HEADERS,
                        hex("82"))));
        assertThrows(IllegalStateException.class,
                () -> assembler.updateMaxDynamicTableSize(0));
    }

    @Test
    void doesNotExposeItsOwnedDecoder() {
        assertThrows(NoSuchMethodException.class,
                () -> HpackFrameAssembler.class.getConstructor(HpackDecoder.class));
        assertThrows(NoSuchMethodException.class,
                () -> HpackFrameAssembler.class.getMethod("decoder"));
    }

    private static HeadersFrame headers(int streamId, int flags, String hex) {
        ByteSequence fragment = sequence(hex(hex));
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
