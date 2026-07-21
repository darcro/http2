package dev.darcro.http2.frame;

/** The fields shared by every nine-byte HTTP/2 frame header. */
public record Http2FrameHeader(int payloadLength, int type, int flags, int streamId) {
    public static final int LENGTH = 9;

    public Http2FrameHeader {
        if (payloadLength < 0 || payloadLength > 0x00ff_ffff) {
            throw new IllegalArgumentException("payloadLength must be an unsigned 24-bit value");
        }
        if (type < 0 || type > 0xff) {
            throw new IllegalArgumentException("type must be an unsigned byte");
        }
        if (flags < 0 || flags > 0xff) {
            throw new IllegalArgumentException("flags must be an unsigned byte");
        }
        if (streamId < 0) {
            throw new IllegalArgumentException("streamId must be an unsigned 31-bit value");
        }
    }
}
