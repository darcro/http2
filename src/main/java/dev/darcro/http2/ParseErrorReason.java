package dev.darcro.http2;

public enum ParseErrorReason {
    TRUNCATED_HEADER,
    LENGTH_MISMATCH,
    FRAME_SIZE_ERROR,
    INVALID_STREAM_ID,
    INVALID_PAYLOAD,
    INVALID_PADDING,
    INVALID_SETTING
}
