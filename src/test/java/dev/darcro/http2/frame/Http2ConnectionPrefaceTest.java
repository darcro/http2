package dev.darcro.http2.frame;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class Http2ConnectionPrefaceTest {
    private static final byte[] PREFACE =
            "PRI * HTTP/2.0\r\n\r\nSM\r\n\r\n".getBytes(StandardCharsets.US_ASCII);

    @Test
    void validatesExactPreface() {
        assertTrue(Http2ConnectionPreface.isValid(PREFACE));
    }

    @Test
    void validatesPrefaceWithinAZeroCopyRange() {
        byte[] surrounding = new byte[PREFACE.length + 5];
        System.arraycopy(PREFACE, 0, surrounding, 3, PREFACE.length);

        assertTrue(Http2ConnectionPreface.isValid(surrounding, 3, PREFACE.length));
        assertFalse(Http2ConnectionPreface.isValid(surrounding));
    }

    @Test
    void rejectsWrongLengthAndChangedBytes() {
        assertFalse(Http2ConnectionPreface.isValid(new byte[0]));
        assertFalse(Http2ConnectionPreface.isValid(
                "PRI * HTTP/2.0\r\n\r\nSM\r\n".getBytes(StandardCharsets.US_ASCII)));

        byte[] changed = PREFACE.clone();
        changed[changed.length - 1] = 0;
        assertFalse(Http2ConnectionPreface.isValid(changed));
    }

    @Test
    void rejectsInvalidArguments() {
        assertThrows(NullPointerException.class,
                () -> Http2ConnectionPreface.isValid(null));
        assertThrows(IndexOutOfBoundsException.class,
                () -> Http2ConnectionPreface.isValid(PREFACE, -1, PREFACE.length));
        assertThrows(IndexOutOfBoundsException.class,
                () -> Http2ConnectionPreface.isValid(PREFACE, 1, PREFACE.length));
    }
}
