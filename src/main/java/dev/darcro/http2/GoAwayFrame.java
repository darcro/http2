package dev.darcro.http2;

/** errorCode is an unsigned 32-bit integer stored in a long. */
public record GoAwayFrame(int length, int flags, int streamId, int lastStreamId,
                          long errorCode, ByteSequence debugData) implements Http2Frame {
    @Override
    public int type() {
        return Http2FrameTypes.GOAWAY;
    }
}
