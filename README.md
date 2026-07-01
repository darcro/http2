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
not track connection state, apply flow control, or validate stream lifecycle and
negotiated settings.

The default maximum payload size is 16,384 bytes. Use
`new Http2FrameParser(maxFrameSize)` after a peer negotiates a larger size.

The fixed client connection preface can be checked without allocation:

```java
boolean valid = Http2ConnectionPreface.isValid(bytes);
boolean validRange = Http2ConnectionPreface.isValid(bytes, offset, 24);
```

## HPACK decoding

`HpackDecoder` implements the stateful RFC 7541 decoding context. Use one
instance for each inbound connection direction. It is separate from the frame
parser and can decode a complete block directly:

```java
HpackDecoder decoder = new HpackDecoder();
List<HpackHeaderField> fields = decoder.decode(headers.headerBlockFragment());
```

For field blocks split over CONTINUATION frames, feed every inbound frame to a
connection-scoped assembler. It returns a value only when END_HEADERS completes
the block:

```java
HpackFrameAssembler assembler = new HpackFrameAssembler(decoder);
Optional<DecodedHeaderBlock> decoded = assembler.accept(frame);
```

Call `decoder.updateMaxDynamicTableSize(value)` when connection code applies a
`SETTINGS_HEADER_TABLE_SIZE` change. Decoder and assembler instances are not
thread-safe. A decoder becomes unusable after a decoding error; an assembler
becomes unusable after either a decoding or frame-sequence error.
