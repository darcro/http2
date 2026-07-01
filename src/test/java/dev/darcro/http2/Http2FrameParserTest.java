package dev.darcro.http2;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import org.junit.jupiter.api.Test;

class Http2FrameParserTest {
    private final Http2FrameParser parser = new Http2FrameParser();

    @Test
    void parsesUnpaddedData() throws Exception {
        DataFrame frame = assertInstanceOf(DataFrame.class,
                parser.parse(frame(0, Http2FrameTypes.DATA, Http2Flags.END_STREAM, 1,
                        1, 2, 3)));

        assertEquals(3, frame.length());
        assertEquals(1, frame.streamId());
        assertTrue(frame.endStream());
        assertFalse(frame.padded());
        assertArrayEquals(new byte[]{1, 2, 3}, frame.data().toByteArray());
    }

    @Test
    void parsesPaddedDataWithoutExposingPadding() throws Exception {
        byte[] input = frame(0, Http2FrameTypes.DATA, Http2Flags.PADDED, 3,
                2, 10, 11, 0, 0);
        DataFrame parsed = assertInstanceOf(DataFrame.class, parser.parse(input));

        assertEquals(2, parsed.paddingLength());
        assertArrayEquals(new byte[]{10, 11}, parsed.data().toByteArray());

        input[10] = 99;
        assertEquals(99, parsed.data().unsignedByteAt(0),
                "payload must be a zero-copy view");
    }

    @Test
    void parsesHeadersWithPriorityAndPadding() throws Exception {
        int flags = Http2Flags.END_HEADERS | Http2Flags.END_STREAM
                | Http2Flags.PRIORITY | Http2Flags.PADDED;
        HeadersFrame frame = assertInstanceOf(HeadersFrame.class,
                parser.parse(frame(0, Http2FrameTypes.HEADERS, flags, 5,
                        1, // pad length
                        0x80, 0, 0, 3, 15, // exclusive, dependency 3, wire weight 15
                        0x82, 0x86, // HPACK fragment
                        0)));

        assertTrue(frame.endHeaders());
        assertTrue(frame.endStream());
        assertTrue(frame.hasPriority());
        assertNotNull(frame.priority());
        assertTrue(frame.priority().exclusive());
        assertEquals(3, frame.priority().streamDependency());
        assertEquals(16, frame.priority().weight());
        assertArrayEquals(new byte[]{(byte) 0x82, (byte) 0x86},
                frame.headerBlockFragment().toByteArray());
    }

    @Test
    void parsesPriorityAndRstStreamUnsignedError() throws Exception {
        PriorityFrame priority = assertInstanceOf(PriorityFrame.class,
                parser.parse(frame(0, Http2FrameTypes.PRIORITY, 0xff, 7,
                        0, 0, 0, 1, 0xff)));
        assertEquals(256, priority.priority().weight());

        RstStreamFrame reset = assertInstanceOf(RstStreamFrame.class,
                parser.parse(frame(0, Http2FrameTypes.RST_STREAM, 0, 7,
                        0xff, 0xff, 0xff, 0xff)));
        assertEquals(0xffff_ffffL, reset.errorCode());
    }

    @Test
    void parsesSettingsAndPreservesUnknownSettings() throws Exception {
        SettingsFrame frame = assertInstanceOf(SettingsFrame.class,
                parser.parse(frame(0, Http2FrameTypes.SETTINGS, 0, 0,
                        0, 1, 0, 0, 0x10, 0,
                        0x12, 0x34, 0xfe, 0xdc, 0xba, 0x98)));

        assertFalse(frame.ack());
        assertEquals(2, frame.settings().size());
        assertEquals(new Setting(1, 4096), frame.settings().get(0));
        assertEquals(new Setting(0x1234, 0xfedc_ba98L), frame.settings().get(1));
        assertThrows(UnsupportedOperationException.class,
                () -> frame.settings().add(new Setting(1, 1)));

        SettingsFrame ack = assertInstanceOf(SettingsFrame.class,
                parser.parse(frame(0, Http2FrameTypes.SETTINGS, Http2Flags.ACK, 0)));
        assertTrue(ack.ack());
        assertTrue(ack.settings().isEmpty());
    }

    @Test
    void parsesPushPromise() throws Exception {
        PushPromiseFrame frame = assertInstanceOf(PushPromiseFrame.class,
                parser.parse(frame(0, Http2FrameTypes.PUSH_PROMISE,
                        Http2Flags.END_HEADERS, 1,
                        0, 0, 0, 2, 0x82)));

        assertEquals(2, frame.promisedStreamId());
        assertTrue(frame.endHeaders());
        assertArrayEquals(new byte[]{(byte) 0x82},
                frame.headerBlockFragment().toByteArray());
    }

    @Test
    void parsesPingGoAwayWindowUpdateAndContinuation() throws Exception {
        PingFrame ping = assertInstanceOf(PingFrame.class,
                parser.parse(frame(0, Http2FrameTypes.PING, Http2Flags.ACK, 0,
                        1, 2, 3, 4, 5, 6, 7, 8)));
        assertTrue(ping.ack());
        assertEquals(8, ping.opaqueData().length());

        GoAwayFrame goAway = assertInstanceOf(GoAwayFrame.class,
                parser.parse(frame(0, Http2FrameTypes.GOAWAY, 0, 0,
                        0x80, 0, 0, 9, 0xff, 0xff, 0xff, 0xff, 10, 11)));
        assertEquals(9, goAway.lastStreamId());
        assertEquals(0xffff_ffffL, goAway.errorCode());
        assertArrayEquals(new byte[]{10, 11}, goAway.debugData().toByteArray());

        WindowUpdateFrame window = assertInstanceOf(WindowUpdateFrame.class,
                parser.parse(frame(0, Http2FrameTypes.WINDOW_UPDATE, 0, 0,
                        0x7f, 0xff, 0xff, 0xff)));
        assertEquals(Integer.MAX_VALUE, window.windowSizeIncrement());

        ContinuationFrame continuation = assertInstanceOf(ContinuationFrame.class,
                parser.parse(frame(0, Http2FrameTypes.CONTINUATION,
                        Http2Flags.END_HEADERS, 9, 1, 2)));
        assertTrue(continuation.endHeaders());
        assertArrayEquals(new byte[]{1, 2}, continuation.headerBlockFragment().toByteArray());
    }

    @Test
    void preservesUnknownFrame() throws Exception {
        UnknownFrame frame = assertInstanceOf(UnknownFrame.class,
                parser.parse(frame(0, 0xfe, 0xa5, 0x7fff_ffff, 3, 4)));

        assertEquals(0xfe, frame.type());
        assertEquals(0xa5, frame.flags());
        assertEquals(Integer.MAX_VALUE, frame.streamId());
        assertArrayEquals(new byte[]{3, 4}, frame.payload().toByteArray());
    }

    @Test
    void parsesAnExactArrayRange() throws Exception {
        byte[] encoded = frame(0, Http2FrameTypes.PING, 0, 0,
                0, 1, 2, 3, 4, 5, 6, 7);
        byte[] surrounding = new byte[encoded.length + 5];
        System.arraycopy(encoded, 0, surrounding, 2, encoded.length);

        PingFrame parsed = assertInstanceOf(PingFrame.class,
                parser.parse(surrounding, 2, encoded.length));
        assertArrayEquals(Arrays.copyOfRange(encoded, 9, 17),
                parsed.opaqueData().toByteArray());
    }

    @Test
    void rejectsTruncationTrailingBytesAndConfiguredSize() {
        ParseErrorException shortHeader = assertThrows(ParseErrorException.class,
                () -> parser.parse(new byte[8]));
        assertEquals(ParseErrorReason.TRUNCATED_HEADER, shortHeader.reason());
        assertTrue(shortHeader.frameType().isEmpty());

        ParseErrorException trailing = assertThrows(ParseErrorException.class,
                () -> parser.parse(frame(1, Http2FrameTypes.DATA, 0, 1, 1, 2)));
        assertEquals(ParseErrorReason.LENGTH_MISMATCH, trailing.reason());

        byte[] oversizedHeader = new byte[9];
        oversizedHeader[0] = 0;
        oversizedHeader[1] = 0x40;
        oversizedHeader[2] = 1;
        ParseErrorException oversized = assertThrows(ParseErrorException.class,
                () -> parser.parse(oversizedHeader));
        assertEquals(ParseErrorReason.FRAME_SIZE_ERROR, oversized.reason());
    }

    @Test
    void rejectsInvalidStreamIdentifiers() {
        assertReason(ParseErrorReason.INVALID_STREAM_ID,
                frame(0, Http2FrameTypes.DATA, 0, 0));
        assertReason(ParseErrorReason.INVALID_STREAM_ID,
                frame(0, Http2FrameTypes.HEADERS, 0, 0));
        assertReason(ParseErrorReason.INVALID_STREAM_ID,
                frame(0, Http2FrameTypes.PRIORITY, 0, 0, 0, 0, 0, 1, 0));
        assertReason(ParseErrorReason.INVALID_STREAM_ID,
                frame(0, Http2FrameTypes.RST_STREAM, 0, 0, 0, 0, 0, 0));
        assertReason(ParseErrorReason.INVALID_STREAM_ID,
                frame(0, Http2FrameTypes.SETTINGS, 0, 1));
        assertReason(ParseErrorReason.INVALID_STREAM_ID,
                frame(0, Http2FrameTypes.PING, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0));
        assertReason(ParseErrorReason.INVALID_STREAM_ID,
                frame(0, Http2FrameTypes.GOAWAY, 0, 1, 0, 0, 0, 0, 0, 0, 0, 0));
        assertReason(ParseErrorReason.INVALID_STREAM_ID,
                frame(0, Http2FrameTypes.CONTINUATION, 0, 0));
    }

    @Test
    void rejectsInvalidFixedLengths() {
        assertReason(ParseErrorReason.FRAME_SIZE_ERROR,
                frame(0, Http2FrameTypes.PRIORITY, 0, 1, 0, 0, 0, 0));
        assertReason(ParseErrorReason.FRAME_SIZE_ERROR,
                frame(0, Http2FrameTypes.RST_STREAM, 0, 1, 0, 0, 0));
        assertReason(ParseErrorReason.FRAME_SIZE_ERROR,
                frame(0, Http2FrameTypes.PING, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        assertReason(ParseErrorReason.FRAME_SIZE_ERROR,
                frame(0, Http2FrameTypes.GOAWAY, 0, 0, 0, 0, 0, 0, 0, 0, 0));
        assertReason(ParseErrorReason.FRAME_SIZE_ERROR,
                frame(0, Http2FrameTypes.WINDOW_UPDATE, 0, 0, 0, 0, 0));
    }

    @Test
    void rejectsInvalidPaddingAndMandatoryFields() {
        assertReason(ParseErrorReason.FRAME_SIZE_ERROR,
                frame(0, Http2FrameTypes.DATA, Http2Flags.PADDED, 1));
        assertReason(ParseErrorReason.INVALID_PADDING,
                frame(0, Http2FrameTypes.DATA, Http2Flags.PADDED, 1, 2, 0));
        assertReason(ParseErrorReason.FRAME_SIZE_ERROR,
                frame(0, Http2FrameTypes.HEADERS, Http2Flags.PRIORITY, 1,
                        0, 0, 0, 0));
        assertReason(ParseErrorReason.FRAME_SIZE_ERROR,
                frame(0, Http2FrameTypes.PUSH_PROMISE, 0, 1, 0, 0, 0));
    }

    @Test
    void rejectsSelfDependencyAndZeroPromisedStream() {
        assertReason(ParseErrorReason.INVALID_STREAM_ID,
                frame(0, Http2FrameTypes.PRIORITY, 0, 3, 0, 0, 0, 3, 0));
        assertReason(ParseErrorReason.INVALID_STREAM_ID,
                frame(0, Http2FrameTypes.HEADERS, Http2Flags.PRIORITY, 3,
                        0, 0, 0, 3, 0));
        assertReason(ParseErrorReason.INVALID_STREAM_ID,
                frame(0, Http2FrameTypes.PUSH_PROMISE, 0, 1, 0, 0, 0, 0));
    }

    @Test
    void rejectsInvalidSettingsAndWindowIncrement() {
        assertReason(ParseErrorReason.FRAME_SIZE_ERROR,
                frame(0, Http2FrameTypes.SETTINGS, 0, 0, 1));
        assertReason(ParseErrorReason.FRAME_SIZE_ERROR,
                frame(0, Http2FrameTypes.SETTINGS, Http2Flags.ACK, 0,
                        0, 1, 0, 0, 0, 1));
        assertReason(ParseErrorReason.INVALID_SETTING,
                frame(0, Http2FrameTypes.SETTINGS, 0, 0,
                        0, 2, 0, 0, 0, 2));
        assertReason(ParseErrorReason.INVALID_SETTING,
                frame(0, Http2FrameTypes.SETTINGS, 0, 0,
                        0, 4, 0x80, 0, 0, 0));
        assertReason(ParseErrorReason.INVALID_SETTING,
                frame(0, Http2FrameTypes.SETTINGS, 0, 0,
                        0, 5, 0, 0, 0x3f, 0xff));
        assertReason(ParseErrorReason.INVALID_PAYLOAD,
                frame(0, Http2FrameTypes.WINDOW_UPDATE, 0, 1, 0, 0, 0, 0));
    }

    @Test
    void validatesParserArgumentsAndByteSequenceBounds() throws Exception {
        assertThrows(NullPointerException.class, () -> parser.parse(null));
        assertThrows(IndexOutOfBoundsException.class,
                () -> parser.parse(new byte[9], 5, 9));
        assertThrows(IllegalArgumentException.class, () -> new Http2FrameParser(16_383));
        assertThrows(IllegalArgumentException.class,
                () -> new Http2FrameParser(Http2FrameParser.MAX_FRAME_SIZE_LIMIT + 1));

        DataFrame frame = assertInstanceOf(DataFrame.class,
                parser.parse(frame(0, Http2FrameTypes.DATA, 0, 1, 1, 2)));
        assertThrows(IndexOutOfBoundsException.class, () -> frame.data().byteAt(2));
        assertThrows(IndexOutOfBoundsException.class, () -> frame.data().slice(1, 2));
    }

    private void assertReason(ParseErrorReason expected, byte[] bytes) {
        ParseErrorException exception = assertThrows(ParseErrorException.class,
                () -> parser.parse(bytes));
        assertEquals(expected, exception.reason(), exception.getMessage());
        assertTrue(exception.frameType().isPresent());
    }

    private static byte[] frame(int declaredLengthAdjustment, int type, int flags,
                                int streamId, int... payload) {
        int declaredLength = payload.length + declaredLengthAdjustment;
        byte[] result = new byte[9 + payload.length];
        result[0] = (byte) (declaredLength >>> 16);
        result[1] = (byte) (declaredLength >>> 8);
        result[2] = (byte) declaredLength;
        result[3] = (byte) type;
        result[4] = (byte) flags;
        result[5] = (byte) (streamId >>> 24);
        result[6] = (byte) (streamId >>> 16);
        result[7] = (byte) (streamId >>> 8);
        result[8] = (byte) streamId;
        for (int i = 0; i < payload.length; i++) {
            result[9 + i] = (byte) payload[i];
        }
        return result;
    }
}
