package dev.darcro.http2;

import java.util.OptionalInt;

/** Indicates an invalid or resource-limited HPACK header block. */
public final class HpackDecodingException extends Exception {
    private final HpackErrorReason reason;
    private final int offset;
    private final int streamId;

    HpackDecodingException(HpackErrorReason reason, int offset, String message) {
        this(reason, offset, -1, message, null);
    }

    private HpackDecodingException(HpackErrorReason reason, int offset, int streamId,
                                   String message, Throwable cause) {
        super(message);
        if (cause != null) {
            initCause(cause);
        }
        this.reason = reason;
        this.offset = offset;
        this.streamId = streamId;
    }

    public HpackErrorReason reason() {
        return reason;
    }

    /** Byte offset relative to the start of the encoded header block. */
    public int offset() {
        return offset;
    }

    /** Stream context when decoding was initiated by a frame assembler. */
    public OptionalInt streamId() {
        return streamId < 0 ? OptionalInt.empty() : OptionalInt.of(streamId);
    }

    HpackDecodingException withStreamId(int streamId) {
        return new HpackDecodingException(reason, offset, streamId, getMessage(), this);
    }
}
