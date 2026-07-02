package dev.darcro.http2.frame;

/** A single parsed HTTP/2 frame. */
public sealed interface Http2Frame permits DataFrame, HeadersFrame, PriorityFrame,
        RstStreamFrame, SettingsFrame, PushPromiseFrame, PingFrame, GoAwayFrame,
        WindowUpdateFrame, ContinuationFrame, UnknownFrame {

    int length();

    int type();

    int flags();

    int streamId();
}
