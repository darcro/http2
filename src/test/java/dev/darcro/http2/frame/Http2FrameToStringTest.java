package dev.darcro.http2.frame;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

class Http2FrameToStringTest {
    @Test
    void rendersEveryConcreteFrameType() {
        ByteSequence bytes = ByteSequence.wrap(new byte[]{0x01, (byte) 0xab});
        PriorityInfo priority = new PriorityInfo(true, 3, 256);

        assertEquals("DataFrame[length=4, type=0x00, flags=0x09, streamId=1, "
                        + "endStream=true, padded=true, paddingLength=1, data=01ab]",
                new DataFrame(4, Http2Flags.END_STREAM | Http2Flags.PADDED,
                        1, bytes, 1).toString());
        assertEquals("HeadersFrame[length=8, type=0x01, flags=0x25, streamId=3, "
                        + "endStream=true, endHeaders=true, padded=false, "
                        + "paddingLength=0, priority=PriorityInfo[exclusive=true, "
                        + "streamDependency=3, weight=256], headerBlockFragment=01ab]",
                new HeadersFrame(8, Http2Flags.END_STREAM | Http2Flags.END_HEADERS
                        | Http2Flags.PRIORITY, 3, bytes, priority, 0).toString());
        assertEquals("PriorityFrame[length=5, type=0x02, flags=0x00, streamId=3, "
                        + "priority=PriorityInfo[exclusive=true, streamDependency=3, "
                        + "weight=256]]",
                new PriorityFrame(5, 0, 3, priority).toString());
        assertEquals("RstStreamFrame[length=4, type=0x03, flags=0x00, streamId=3, "
                        + "errorCode=0x00000008]",
                new RstStreamFrame(4, 0, 3, 8).toString());
        assertEquals("SettingsFrame[length=6, type=0x04, flags=0x00, streamId=0, "
                        + "ack=false, settings=[Setting[identifier=1, value=4096]]]",
                new SettingsFrame(6, 0, 0, List.of(new Setting(1, 4096))).toString());
        assertEquals("PushPromiseFrame[length=6, type=0x05, flags=0x04, streamId=1, "
                        + "endHeaders=true, padded=false, paddingLength=0, "
                        + "promisedStreamId=2, headerBlockFragment=01ab]",
                new PushPromiseFrame(6, Http2Flags.END_HEADERS, 1, 2,
                        bytes, 0).toString());
        assertEquals("PingFrame[length=8, type=0x06, flags=0x01, streamId=0, "
                        + "ack=true, opaqueData=01ab]",
                new PingFrame(8, Http2Flags.ACK, 0, bytes).toString());
        assertEquals("GoAwayFrame[length=10, type=0x07, flags=0x00, streamId=0, "
                        + "lastStreamId=3, errorCode=0xffffffff, debugData=01ab]",
                new GoAwayFrame(10, 0, 0, 3, 0xffff_ffffL, bytes).toString());
        assertEquals("WindowUpdateFrame[length=4, type=0x08, flags=0x00, streamId=3, "
                        + "windowSizeIncrement=1024]",
                new WindowUpdateFrame(4, 0, 3, 1024).toString());
        assertEquals("ContinuationFrame[length=2, type=0x09, flags=0x04, streamId=3, "
                        + "endHeaders=true, headerBlockFragment=01ab]",
                new ContinuationFrame(2, Http2Flags.END_HEADERS, 3, bytes).toString());
        assertEquals("UnknownFrame[length=2, type=0x0a, flags=0xff, streamId=3, "
                        + "payload=01ab]",
                new UnknownFrame(2, 0x0a, 0xff, 3, bytes).toString());
    }

    @Test
    void boundsBinaryContentPreviews() {
        byte[] content = new byte[70];
        for (int i = 0; i < content.length; i++) {
            content[i] = (byte) i;
        }

        assertEquals("DataFrame[length=70, type=0x00, flags=0x00, streamId=1, "
                        + "endStream=false, padded=false, paddingLength=0, data="
                        + "000102030405060708090a0b0c0d0e0f"
                        + "101112131415161718191a1b1c1d1e1f"
                        + "202122232425262728292a2b2c2d2e2f"
                        + "303132333435363738393a3b3c3d3e3f...(+6 bytes)]",
                new DataFrame(70, 0, 1, ByteSequence.wrap(content), 0).toString());
    }
}
