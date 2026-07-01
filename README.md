# HTTP/2 Frame Parser

A small Java 17 library for parsing one complete HTTP/2 frame from its wire
representation. It supports all frame types defined by RFC 9113 and returns
unknown extension types without interpreting their payloads.

```java
Http2FrameParser parser = new Http2FrameParser();
Http2Frame frame = parser.parse(wireBytes);

if (frame instanceof DataFrame data) {
    byte firstByte = data.data().byteAt(0);
}
```

Parsing is zero-copy: payloads are exposed as read-only `ByteSequence` views.
Do not modify the input array while a parsed frame is in use. Call
`ByteSequence.toByteArray()` when an independently owned copy is required.

The parser validates frame-local structure and semantics. It intentionally does
not track connection state, reassemble CONTINUATION frames, decode HPACK, apply
flow control, or validate stream lifecycle and negotiated settings.

The default maximum payload size is 16,384 bytes. Use
`new Http2FrameParser(maxFrameSize)` after a peer negotiates a larger size.
