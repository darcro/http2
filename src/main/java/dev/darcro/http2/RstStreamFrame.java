package dev.darcro.http2;

/** errorCode is an unsigned 32-bit integer stored in a long. */
public record RstStreamFrame(int length, int flags, int streamId,
                             long errorCode) implements Http2Frame {
    @Override
    public int type() {
        return Http2FrameTypes.RST_STREAM;
    }
}
