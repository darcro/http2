# HTTP/2 Passive Analysis Library

A dependency-free Java 17 library for inspecting HTTP/2 frames and HPACK
header blocks from captured traffic. It is designed for passive analysis: the
caller reconstructs TCP, separates connection directions, and supplies exact
candidate frames in observation order. The library preserves raw evidence,
reports diagnostics without becoming unusable, and records the provenance of
decoded header names and values.

The single JAR exposes frame APIs under `dev.darcro.http2.frame` and HPACK APIs
under `dev.darcro.http2.hpack`.

```java
Http2FrameParser parser = new Http2FrameParser();
Http2FrameObservation observation = parser.observe(candidateFrameBytes);

observation.frame().ifPresent(frame -> {
    HpackFrameAnalysis result = assembler.accept(frame);
    result.decodedBlock().ifPresent(block ->
            block.headerFields().first("content-type").ifPresent(field ->
                    System.out.println(field.valueUtf8())));
});
```

`observe` is the normal passive-analysis entry point. It returns the raw bytes,
the frame header when available, an optional typed frame, and diagnostics.
Strict `parse` methods remain available when rejection on malformed input is
preferred.

## Documentation

- [Simple usage guide](docs/usage.md) — frame observation, HPACK assembly,
  diagnostics, and header provenance.
- [Advanced guide](docs/advanced.md) — limits, incomplete capture context,
  recovery semantics, snapshots, and ownership.
- [Developer guide](docs/development.md) — architecture, invariants, testing,
  maintenance, and release publication.

## Requirements

- Java 17 or newer
- Maven 3.8 or newer for building from source

Build and run the complete test suite with:

```shell
mvn clean verify
```

The project has no runtime dependencies.

## GitHub Packages

Tagged releases are published to GitHub Packages by the Maven workflow. A tag
named `v0.1.0` publishes artifact version `0.1.0`.

```xml
<repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/darcro/http2</url>
</repository>

<dependency>
    <groupId>dev.darcro.http2</groupId>
    <artifactId>http2-parser</artifactId>
    <version>0.1.0</version>
</dependency>
```

GitHub Packages may require Maven credentials depending on package visibility.
