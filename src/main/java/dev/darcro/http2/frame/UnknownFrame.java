package dev.darcro.http2.frame;

public record UnknownFrame(int length, int type, int flags, int streamId,
                           ByteSequence payload) implements Http2Frame {
    @Override
    public String toString() {
        return FrameText.start("UnknownFrame", this)
                .append(", payload=").append(FrameText.hex(payload)).append(']')
                .toString();
    }
}
