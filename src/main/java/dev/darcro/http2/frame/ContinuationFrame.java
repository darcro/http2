package dev.darcro.http2.frame;

public record ContinuationFrame(int length, int flags, int streamId,
                                ByteSequence headerBlockFragment) implements Http2Frame {
    @Override
    public int type() {
        return Http2FrameTypes.CONTINUATION;
    }

    public boolean endHeaders() {
        return (flags & Http2Flags.END_HEADERS) != 0;
    }

    @Override
    public String toString() {
        return FrameText.start("ContinuationFrame", this)
                .append(", endHeaders=").append(endHeaders())
                .append(", headerBlockFragment=")
                .append(FrameText.hex(headerBlockFragment)).append(']').toString();
    }
}
