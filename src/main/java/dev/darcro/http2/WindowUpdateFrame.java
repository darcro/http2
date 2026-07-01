package dev.darcro.http2;

public record WindowUpdateFrame(int length, int flags, int streamId,
                                int windowSizeIncrement) implements Http2Frame {
    @Override
    public int type() {
        return Http2FrameTypes.WINDOW_UPDATE;
    }
}
