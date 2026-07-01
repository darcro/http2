package dev.darcro.http2;

/** Flag bit masks used by RFC 9113 frame types. */
public final class Http2Flags {
    public static final int ACK = 0x01;
    public static final int END_STREAM = 0x01;
    public static final int END_HEADERS = 0x04;
    public static final int PADDED = 0x08;
    public static final int PRIORITY = 0x20;

    private Http2Flags() {
    }
}
