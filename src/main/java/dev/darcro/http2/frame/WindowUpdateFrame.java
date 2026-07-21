package dev.darcro.http2.frame;

public record WindowUpdateFrame(int length, int flags, int streamId,
                                int windowSizeIncrement) implements Http2Frame {
    @Override
    public int type() {
        return Http2FrameTypes.WINDOW_UPDATE;
    }

    @Override
    public String toString() {
        return FrameText.start("WindowUpdateFrame", this)
                .append(", windowSizeIncrement=").append(windowSizeIncrement)
                .append(']').toString();
    }
}
