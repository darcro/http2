package dev.darcro.http2.hpack;

import dev.darcro.http2.frame.ByteSequence;
import java.util.Arrays;

/** An immutable dynamic-table entry stored in an HPACK decoder snapshot. */
public final class HpackDynamicTableEntry {
    private final byte[] nameBytes;
    private final byte[] valueBytes;
    private final ByteSequence name;
    private final ByteSequence value;

    HpackDynamicTableEntry(byte[] name, byte[] value) {
        this.nameBytes = name.clone();
        this.valueBytes = value.clone();
        this.name = ByteSequence.wrap(nameBytes);
        this.value = ByteSequence.wrap(valueBytes);
    }

    public ByteSequence name() {
        return name;
    }

    public ByteSequence value() {
        return value;
    }

    public int size() {
        return nameBytes.length + valueBytes.length + 32;
    }

    byte[] nameBytes() {
        return nameBytes.clone();
    }

    byte[] valueBytes() {
        return valueBytes.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof HpackDynamicTableEntry that
                && Arrays.equals(nameBytes, that.nameBytes)
                && Arrays.equals(valueBytes, that.valueBytes);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(nameBytes) + Arrays.hashCode(valueBytes);
    }

    @Override
    public String toString() {
        return "HpackDynamicTableEntry[nameLength=" + nameBytes.length
                + ", valueLength=" + valueBytes.length + ']';
    }
}
