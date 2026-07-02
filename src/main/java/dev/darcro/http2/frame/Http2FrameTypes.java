package dev.darcro.http2.frame;

/** HTTP/2 frame type codes defined by RFC 9113. */
public final class Http2FrameTypes {
    public static final int DATA = 0x00;
    public static final int HEADERS = 0x01;
    public static final int PRIORITY = 0x02;
    public static final int RST_STREAM = 0x03;
    public static final int SETTINGS = 0x04;
    public static final int PUSH_PROMISE = 0x05;
    public static final int PING = 0x06;
    public static final int GOAWAY = 0x07;
    public static final int WINDOW_UPDATE = 0x08;
    public static final int CONTINUATION = 0x09;

    private Http2FrameTypes() {
    }
}
