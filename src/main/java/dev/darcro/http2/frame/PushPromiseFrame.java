package dev.darcro.http2.frame;

public record PushPromiseFrame(int length, int flags, int streamId,
                               int promisedStreamId, ByteSequence headerBlockFragment,
                               int paddingLength) implements Http2Frame {
    @Override
    public int type() {
        return Http2FrameTypes.PUSH_PROMISE;
    }

    public boolean endHeaders() {
        return (flags & Http2Flags.END_HEADERS) != 0;
    }

    public boolean padded() {
        return (flags & Http2Flags.PADDED) != 0;
    }
}
