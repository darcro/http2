package dev.darcro.http2;

import java.util.Arrays;
import java.util.Objects;

/**
 * A read-only view of a contiguous region of a byte array.
 *
 * <p>The view does not copy its input. Callers must not modify the source array
 * while this object or a parsed frame referring to it is in use.</p>
 */
public final class ByteSequence {
    private final byte[] bytes;
    private final int offset;
    private final int length;

    ByteSequence(byte[] bytes, int offset, int length) {
        this.bytes = Objects.requireNonNull(bytes, "bytes");
        Objects.checkFromIndexSize(offset, length, bytes.length);
        this.offset = offset;
        this.length = length;
    }

    public int length() {
        return length;
    }

    public boolean isEmpty() {
        return length == 0;
    }

    public byte byteAt(int index) {
        Objects.checkIndex(index, length);
        return bytes[offset + index];
    }

    public int unsignedByteAt(int index) {
        return Byte.toUnsignedInt(byteAt(index));
    }

    public ByteSequence slice(int fromIndex, int sliceLength) {
        Objects.checkFromIndexSize(fromIndex, sliceLength, length);
        return new ByteSequence(bytes, offset + fromIndex, sliceLength);
    }

    public void copyTo(byte[] destination, int destinationOffset) {
        Objects.requireNonNull(destination, "destination");
        Objects.checkFromIndexSize(destinationOffset, length, destination.length);
        System.arraycopy(bytes, offset, destination, destinationOffset, length);
    }

    public byte[] toByteArray() {
        return Arrays.copyOfRange(bytes, offset, offset + length);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ByteSequence that) || length != that.length) {
            return false;
        }
        return Arrays.equals(bytes, offset, offset + length,
                that.bytes, that.offset, that.offset + that.length);
    }

    @Override
    public int hashCode() {
        int result = 1;
        for (int i = 0; i < length; i++) {
            result = 31 * result + bytes[offset + i];
        }
        return result;
    }

    @Override
    public String toString() {
        return "ByteSequence[length=" + length + ']';
    }
}
