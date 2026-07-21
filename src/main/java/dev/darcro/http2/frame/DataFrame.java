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

    @Override
    public String toString() {
        return FrameText.start("DataFrame", this)
                .append(", endStream=").append(endStream())
                .append(", padded=").append(padded())
                .append(", paddingLength=").append(paddingLength)
                .append(", data=").append(FrameText.hex(data)).append(']').toString();
    }
}
