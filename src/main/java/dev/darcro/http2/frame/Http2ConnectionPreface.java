package dev.darcro.http2.frame;

import java.util.Arrays;
import java.util.Objects;

/** Validates the fixed 24-byte HTTP/2 client connection preface. */
public final class Http2ConnectionPreface {
    public static final int LENGTH = 24;

    private static final byte[] BYTES = {
            'P', 'R', 'I', ' ', '*', ' ', 'H', 'T',
            'T', 'P', '/', '2', '.', '0', '\r', '\n',
            '\r', '\n', 'S', 'M', '\r', '\n', '\r', '\n'
    };

    private Http2ConnectionPreface() {
    }

    /** Returns true only when the array contains exactly the client preface. */
    public static boolean isValid(byte[] bytes) {
        Objects.requireNonNull(bytes, "bytes");
        return isValid(bytes, 0, bytes.length);
    }

    /**
     * Returns true only when the selected range contains exactly the client preface.
     * The comparison performs no allocation.
     */
    public static boolean isValid(byte[] bytes, int offset, int length) {
        Objects.requireNonNull(bytes, "bytes");
        Objects.checkFromIndexSize(offset, length, bytes.length);
        return length == LENGTH
                && Arrays.equals(bytes, offset, offset + LENGTH, BYTES, 0, LENGTH);
    }
}
