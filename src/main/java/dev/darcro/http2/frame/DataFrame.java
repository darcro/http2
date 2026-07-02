package dev.darcro.http2.frame;

public record DataFrame(int length, int flags, int streamId, ByteSequence data,
                        int paddingLength) implements Http2Frame {
    @Override
    public int type() {
        return Http2FrameTypes.DATA;
    }

    public boolean endStream() {
        return (flags & Http2Flags.END_STREAM) != 0;
    }

    public boolean padded() {
        return (flags & Http2Flags.PADDED) != 0;
    }
}
