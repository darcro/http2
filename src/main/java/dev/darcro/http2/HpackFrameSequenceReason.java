package dev.darcro.http2;

public enum HpackFrameSequenceReason {
    ASSEMBLER_FAILED,
    UNEXPECTED_CONTINUATION,
    INTERLEAVED_FRAME,
    WRONG_STREAM
}
