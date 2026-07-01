package dev.darcro.http2;

public record HeadersFrame(int length, int flags, int streamId,
                           ByteSequence headerBlockFragment, PriorityInfo priority,
                           int paddingLength) implements Http2Frame {
    @Override
    public int type() {
        return Http2FrameTypes.HEADERS;
    }

    public boolean endStream() {
        return (flags & Http2Flags.END_STREAM) != 0;
    }

    public boolean endHeaders() {
        return (flags & Http2Flags.END_HEADERS) != 0;
    }

    public boolean padded() {
        return (flags & Http2Flags.PADDED) != 0;
    }

    public boolean hasPriority() {
        return priority != null;
    }
}
