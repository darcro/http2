package dev.darcro.http2.frame;

public record PriorityFrame(int length, int flags, int streamId,
                            PriorityInfo priority) implements Http2Frame {
    @Override
    public int type() {
        return Http2FrameTypes.PRIORITY;
    }
}
