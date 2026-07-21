package dev.darcro.http2.frame;

/** errorCode is an unsigned 32-bit integer stored in a long. */
public record GoAwayFrame(int length, int flags, int streamId, int lastStreamId,
                          long errorCode, ByteSequence debugData) implements Http2Frame {
    @Override
    public int type() {
        return Http2FrameTypes.GOAWAY;
    }

    @Override
    public String toString() {
        return FrameText.start("GoAwayFrame", this)
                .append(", lastStreamId=").append(lastStreamId)
                .append(", errorCode=").append(FrameText.hexUnsignedInt(errorCode))
                .append(", debugData=").append(FrameText.hex(debugData))
                .append(']').toString();
    }
}
