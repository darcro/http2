package dev.darcro.http2.hpack;

import java.util.List;
import java.util.OptionalInt;

/** Immutable, inspectable state for resuming one HPACK decoding context. */
public final class HpackDecoderSnapshot {
    public static final int FORMAT_VERSION = 1;

    private final int dynamicTableLimit;
    private final int maximumTableSize;
    private final int pendingMinimum;
    private final List<HpackDynamicTableEntry> dynamicTableEntries;

    HpackDecoderSnapshot(int dynamicTableLimit, int maximumTableSize,
                         int pendingMinimum,
                         List<HpackDynamicTableEntry> dynamicTableEntries) {
        this.dynamicTableLimit = dynamicTableLimit;
        this.maximumTableSize = maximumTableSize;
        this.pendingMinimum = pendingMinimum;
        this.dynamicTableEntries = List.copyOf(dynamicTableEntries);
    }

    public int dynamicTableLimit() {
        return dynamicTableLimit;
    }

    public int maximumTableSize() {
        return maximumTableSize;
    }

    public OptionalInt pendingMinimum() {
        return pendingMinimum < 0 ? OptionalInt.empty() : OptionalInt.of(pendingMinimum);
    }

    /** Entries in HPACK index order, newest first. */
    public List<HpackDynamicTableEntry> dynamicTableEntries() {
        return dynamicTableEntries;
    }

    public int dynamicTableSize() {
        int size = 0;
        for (HpackDynamicTableEntry entry : dynamicTableEntries) {
            size += entry.size();
        }
        return size;
    }

    public byte[] toByteArray() {
        return HpackSnapshotCodec.encode(this);
    }

    public static HpackDecoderSnapshot fromByteArray(byte[] encoded)
            throws HpackSnapshotException {
        return HpackSnapshotCodec.decodeDecoder(encoded);
    }
}
