package dev.darcro.http2.hpack;

/** Indicates an invalid HEADERS/PUSH_PROMISE/CONTINUATION frame sequence. */
public final class HpackFrameSequenceException extends Exception {
    private final HpackFrameSequenceReason reason;
    private final int streamId;

    HpackFrameSequenceException(HpackFrameSequenceReason reason, int streamId,
                                String message) {
        super(message);
        this.reason = reason;
        this.streamId = streamId;
    }

    public HpackFrameSequenceReason reason() {
        return reason;
    }

    public int streamId() {
        return streamId;
    }
}
