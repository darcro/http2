package dev.darcro.http2.frame;

import java.util.OptionalInt;

/** Indicates that input bytes do not form a valid, frame-local HTTP/2 frame. */
public final class ParseErrorException extends Exception {
    private final ParseErrorReason reason;
    private final int offset;
    private final int frameType;

    ParseErrorException(ParseErrorReason reason, int offset, int frameType, String message) {
        super(message);
        this.reason = reason;
        this.offset = offset;
        this.frameType = frameType;
    }

    public ParseErrorReason reason() {
        return reason;
    }

    /** Returns the offset relative to the start of the supplied frame range. */
    public int offset() {
        return offset;
    }

    public OptionalInt frameType() {
        return frameType < 0 ? OptionalInt.empty() : OptionalInt.of(frameType);
    }
}
