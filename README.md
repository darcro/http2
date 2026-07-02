# HTTP/2 Frame Parser

A dependency-free Java 17 library for parsing HTTP/2 frames and decoding HPACK
header blocks. It supports every frame type defined by RFC 9113, preserves
unknown extension frames, validates the HTTP/2 client preface, and implements
stateful RFC 7541 HPACK decoding with offline snapshot and restore support.

The single JAR exposes frame APIs under `dev.darcro.http2.frame` and HPACK APIs
under `dev.darcro.http2.hpack`.

```java
import dev.darcro.http2.frame.DataFrame;
import dev.darcro.http2.frame.Http2Frame;
import dev.darcro.http2.frame.Http2FrameParser;

Http2Frame frame = new Http2FrameParser().parse(wireBytes);

if (frame instanceof DataFrame data) {
    byte[] content = data.data().toByteArray();
}
```

## Documentation

- [Simple usage guide](docs/usage.md) — installation, frame parsing, preface
  validation, and basic HPACK decoding.
- [Advanced configuration guide](docs/advanced.md) — limits, negotiated
  settings, state management, zero-copy behavior, and error handling.
- [Developer guide](docs/development.md) — architecture, protocol invariants,
  tests, maintenance workflows, and extension points.

## Requirements

- Java 17 or newer
- Maven 3.8 or newer for building from source

Build and run the complete test suite with:

```shell
mvn clean verify
```

The project has no runtime dependencies.
