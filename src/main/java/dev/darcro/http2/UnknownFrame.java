package dev.darcro.http2;

public record UnknownFrame(int length, int type, int flags, int streamId,
                           ByteSequence payload) implements Http2Frame {
}
