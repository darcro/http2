package dev.darcro.http2.frame;

public record PingFrame(int length, int flags, int streamId,
                        ByteSequence opaqueData) implements Http2Frame {
    @Override
    public int type() {
        return Http2FrameTypes.PING;
    }

    public boolean ack() {
        return (flags & Http2Flags.ACK) != 0;
    }
}
