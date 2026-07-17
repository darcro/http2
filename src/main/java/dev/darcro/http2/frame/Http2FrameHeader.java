package dev.darcro.http2.frame;

/** The fields shared by every nine-byte HTTP/2 frame header. */
public record Http2FrameHeader(int payloadLength, int type, int flags, int streamId) {
}
