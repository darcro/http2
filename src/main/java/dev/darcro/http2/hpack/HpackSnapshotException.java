package dev.darcro.http2.hpack;

/** Indicates that a persisted HPACK snapshot is invalid or cannot be restored. */
public final class HpackSnapshotException extends Exception {
    private final HpackSnapshotErrorReason reason;
    private final int offset;

    HpackSnapshotException(HpackSnapshotErrorReason reason, int offset, String message) {
        super(message);
        this.reason = reason;
        this.offset = offset;
    }

    public HpackSnapshotErrorReason reason() {
        return reason;
    }

    /** Byte offset in the encoded snapshot, or -1 for restore-policy failures. */
    public int offset() {
        return offset;
    }
}
