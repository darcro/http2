package dev.darcro.http2.frame;

/**
 * A single parsed HTTP/2 frame. Concrete frame strings include header fields
 * and frame-specific content; binary fields are rendered as a bounded hex
 * preview so logging cannot accidentally emit an entire large payload.
 */
public sealed interface Http2Frame permits DataFrame, HeadersFrame, PriorityFrame,
        RstStreamFrame, SettingsFrame, PushPromiseFrame, PingFrame, GoAwayFrame,
        WindowUpdateFrame, ContinuationFrame, UnknownFrame {

    int length();

    int type();

    int flags();

    int streamId();
}
