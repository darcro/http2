# Simple Usage Guide

This guide covers the common operations: parsing one HTTP/2 frame, reading its
payload, validating the client connection preface, and decoding headers.

## Add the library

The current Maven coordinates are:

```xml
<dependency>
    <groupId>dev.darcro.http2</groupId>
    <artifactId>http2-parser</artifactId>
    <version>0.1.0-SNAPSHOT</version>
</dependency>
```

Until the artifact is published to a repository, clone this project and install
it into your local Maven repository:

```shell
mvn clean install
```

The library requires Java 17 and has no runtime dependencies.

## Parse one frame

`Http2FrameParser` accepts exactly one complete frame: the 9-byte HTTP/2 frame
header followed by the number of payload bytes declared in that header.

```java
import dev.darcro.http2.frame.DataFrame;
import dev.darcro.http2.frame.Http2Frame;
import dev.darcro.http2.frame.Http2FrameParser;
import dev.darcro.http2.frame.ParseErrorException;

Http2FrameParser parser = new Http2FrameParser();

try {
    Http2Frame frame = parser.parse(wireBytes);

    if (frame instanceof DataFrame data) {
        System.out.println("stream=" + data.streamId());
        System.out.println("endStream=" + data.endStream());
        byte[] body = data.data().toByteArray();
    }
} catch (ParseErrorException error) {
    System.err.println("Invalid frame at byte " + error.offset()
            + ": " + error.getMessage());
}
```

The returned type identifies the frame payload:

| Wire type | Java type |
| --- | --- |
| DATA | `DataFrame` |
| HEADERS | `HeadersFrame` |
| PRIORITY | `PriorityFrame` |
| RST_STREAM | `RstStreamFrame` |
| SETTINGS | `SettingsFrame` |
| PUSH_PROMISE | `PushPromiseFrame` |
| PING | `PingFrame` |
| GOAWAY | `GoAwayFrame` |
| WINDOW_UPDATE | `WindowUpdateFrame` |
| CONTINUATION | `ContinuationFrame` |
| Extension or unknown type | `UnknownFrame` |

Payloads are exposed as read-only `ByteSequence` views. Parsing does not copy
payload bytes, so do not modify the input array while the parsed frame is in
use. Call `toByteArray()` when independently owned bytes are needed.

## Validate the client preface

The HTTP/2 client connection preface is not a frame. Validate its fixed 24-byte
value separately:

```java
import dev.darcro.http2.frame.Http2ConnectionPreface;

boolean valid = Http2ConnectionPreface.isValid(prefaceBytes);
```

A range overload avoids copying when the preface is inside a larger buffer:

```java
boolean valid = Http2ConnectionPreface.isValid(buffer, offset,
        Http2ConnectionPreface.LENGTH);
```

The input must begin at the HTTP/2 application layer. Ethernet, IP, and TCP
headers are outside this library's scope.

## Decode a complete HPACK block

HEADERS and PUSH_PROMISE frames contain HPACK-encoded field-block fragments.
When `END_HEADERS` is set, that frame contains the complete block and it can be
decoded directly:

```java
import dev.darcro.http2.frame.HeadersFrame;
import dev.darcro.http2.hpack.HpackDecoder;
import dev.darcro.http2.hpack.HpackHeaderField;

HpackDecoder decoder = new HpackDecoder();

if (frame instanceof HeadersFrame headers && headers.endHeaders()) {
    for (HpackHeaderField field : decoder.decode(headers.headerBlockFragment())) {
        System.out.println(field.nameUtf8() + ": " + field.valueUtf8());
    }
}
```

HPACK is stateful. Reuse the same decoder for successive blocks received in one
connection direction. Use a separate decoder for the opposite direction and
for every other connection.

## Decode fragmented headers

Use `HpackFrameAssembler` when a field block can continue across CONTINUATION
frames. Feed every inbound frame to the assembler so it can detect illegal
interleaving:

```java
import dev.darcro.http2.hpack.DecodedHeaderBlock;
import dev.darcro.http2.hpack.HpackDecoder;
import dev.darcro.http2.hpack.HpackFrameAssembler;
import java.util.Optional;

HpackDecoder decoder = new HpackDecoder();
HpackFrameAssembler assembler = new HpackFrameAssembler(decoder);

Http2Frame frame = parser.parse(wireBytes);
Optional<DecodedHeaderBlock> completed = assembler.accept(frame);

if (completed.isPresent()) {
    DecodedHeaderBlock block = completed.get();
    System.out.println("headers for stream " + block.streamId());
    block.fields().forEach(field ->
            System.out.println(field.nameUtf8() + ": " + field.valueUtf8()));
}
```

Both parsing and HPACK APIs use checked exceptions because their inputs are
untrusted wire data. See the [advanced guide](advanced.md) for configuration,
error categories, SETTINGS handling, and failed-state behavior.
