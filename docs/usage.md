# Simple Usage Guide

This guide covers the common operations: parsing one HTTP/2 frame, reading its
payload, validating the client connection preface, and decoding headers.

## Add the library

Release artifacts are published to GitHub Packages. Add the package repository
and dependency:

```xml
<repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/darcro/http2</url>
</repository>
```

```xml
<dependency>
    <groupId>dev.darcro.http2</groupId>
    <artifactId>http2-parser</artifactId>
    <version>0.1.0</version>
</dependency>
```

GitHub Packages may require Maven credentials depending on package visibility.

For local development from a checkout, install the current snapshot into your
local Maven repository:

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

## Decode headers

`HpackFrameAssembler` owns the stateful HPACK decoder and handles complete or
fragmented field blocks. Use one assembler for each inbound connection
direction and feed every inbound frame to it in wire order. Passing non-header
frames is required so the assembler can detect illegal interleaving between a
HEADERS or PUSH_PROMISE and its CONTINUATION frames.

```java
import dev.darcro.http2.hpack.DecodedHeaderBlock;
import dev.darcro.http2.hpack.HpackFrameAssembler;
import java.util.Optional;

HpackFrameAssembler assembler = new HpackFrameAssembler();

Http2Frame frame = parser.parse(wireBytes);
Optional<DecodedHeaderBlock> completed = assembler.accept(frame);

if (completed.isPresent()) {
    DecodedHeaderBlock block = completed.get();
    System.out.println("headers for stream " + block.streamId());
    if (block.recovered()) {
        System.out.println("partial header block: " + block.recoveryEvents());
    }
    block.method().ifPresent(method -> System.out.println("method=" + method));
    block.status().ifPresent(status -> System.out.println("status=" + status));

    block.headerFields().first("content-type").ifPresent(field ->
            System.out.println("content-type=" + field.valueUtf8()));
}
```

The request pseudo-fields `method()`, `scheme()`, `authority()`, and `path()`,
and the response pseudo-field `status()`, are cached when a block is created.
For any other name, `headerFields().first(name)`, `all(name)`, and
`contains(name)` perform ASCII case-insensitive lookup. `all(name)` preserves
duplicate values in wire order.

Do not create a separate decoder for frames handled by an assembler. Direct
`HpackDecoder` use is available for lower-level integrations that already own
complete HPACK field blocks. Those callers can create the same lookup view with
`HpackHeaderFields.copyOf(decoder.decode(headerBlock))`.

When captured traffic starts in the middle of an existing HTTP/2 connection,
the local HPACK dynamic table can be missing entries referenced by later
frames. By default the decoder skips only those unavailable dynamic-table
references, reports them through `recoveryEvents()`, and keeps processing later
fields and frames. Other malformed HPACK data still raises a checked exception.

Both parsing and HPACK APIs use checked exceptions because their inputs are
untrusted wire data. See the [advanced guide](advanced.md) for configuration,
error categories, SETTINGS handling, failed-state behavior, and resuming an
offline capture from persisted HPACK state.
