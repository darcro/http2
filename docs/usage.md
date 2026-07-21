# Simple Usage Guide

This library analyzes HTTP/2 application-layer data. TCP reassembly, TLS
decryption, connection identification, and separation of the two connection
directions belong to the caller. Supply either ordered payload chunks for one
direction to the extractor or one exact candidate frame at a time to the
parser.

## Add the library

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

For a checkout, run `mvn clean install`. The library requires Java 17 and has
no runtime dependencies.

## Extract frames from payload chunks

Use one `Http2FrameExtractor` for each connection direction. Input must already
be ordered, deduplicated, contiguous TCP payload bytes, but chunks may split a
preface, frame header, or payload anywhere and may contain multiple frames.

```java
HpackFrameAssembler assembler = new HpackFrameAssembler();

Http2FrameExtractor extractor = new Http2FrameExtractor(event -> {
    if (event instanceof Http2ConnectionPrefaceExtracted preface) {
        System.out.println("preface at " + preface.streamOffset());
    } else if (event instanceof Http2FrameExtracted extracted) {
        System.out.println("frame at " + extracted.streamOffset()
                + " via " + extracted.boundaryProvenance());
        extracted.observation().frame().ifPresent(assembler::accept);
    } else if (event instanceof Http2FrameCandidateRejected rejected) {
        System.out.println("rejected candidate at " + rejected.streamOffset());
        rejected.observation().diagnostics().forEach(System.out::println);
    } else if (event instanceof Http2ExtractionDiagnostic diagnostic) {
        System.out.println(diagnostic);
    }
});

extractor.accept(firstPayloadChunk);
extractor.accept(nextPayloadChunk, offset, length);
extractor.finish();
```

An exact client connection preface at stream offset zero establishes frame
alignment immediately. Otherwise the extractor waits for two consecutive,
valid standard frames before emitting them. This conservative confirmation
reduces false matches when capture starts inside a frame. Unknown extension
frames are emitted after alignment but do not establish it.

Events are delivered synchronously in stream order. Extracted and rejected
observations own their bytes and may be retained after the callback. A malformed
candidate encountered after alignment is reported, then the extractor searches
for a new two-frame confirmation. Call `finish()` to report incomplete trailing
data; no further input is accepted afterward.

## Observe a frame

`Http2FrameParser.observe` retains both evidence and interpretation. The input
must contain the nine-byte frame header and exactly the declared payload.

```java
Http2FrameParser parser = new Http2FrameParser();
Http2FrameObservation observation = parser.observe(candidateBytes);

System.out.println("captured bytes=" + observation.rawBytes().length());
observation.header().ifPresent(header ->
        System.out.println("stream=" + header.streamId()));
observation.diagnostics().forEach(System.out::println);

observation.frame().ifPresent(frame -> {
    if (frame instanceof DataFrame data) {
        byte[] body = data.data().toByteArray();
    }
});
```

The optional header can be present even when the payload cannot be converted
to a typed frame. Unknown extension types become `UnknownFrame`. Standard wire
types become `DataFrame`, `HeadersFrame`, `PriorityFrame`, `RstStreamFrame`,
`SettingsFrame`, `PushPromiseFrame`, `PingFrame`, `GoAwayFrame`,
`WindowUpdateFrame`, or `ContinuationFrame`.

`observe` is zero-copy: its byte sequences refer to the supplied array. Keep
that array unchanged for as long as the observation or frame is used. Use
`observeOwned` when the library should make one defensive copy. Use `parse`
when a malformed candidate should instead throw `ParseErrorException`.

## Validate the client preface

The fixed 24-byte client connection preface is not an HTTP/2 frame.

```java
boolean valid = Http2ConnectionPreface.isValid(prefaceBytes);
boolean rangeValid = Http2ConnectionPreface.isValid(
        buffer, offset, Http2ConnectionPreface.LENGTH);
```

## Assemble and decode headers

Create one `HpackFrameAssembler` per observed connection direction. The default
constructor assumes that capture may have begun mid-connection, so its HPACK
context is `PARTIAL`. No input-gap notification is required or available.

```java
List<HpackDiagnostic> diagnostics = new ArrayList<>();
HpackFrameAssembler assembler = new HpackFrameAssembler(
        HpackDecoderConfig.defaults(), diagnostics::add);

Http2FrameObservation observation = parser.observe(candidateBytes);
observation.frame().ifPresent(frame -> {
    HpackFrameAnalysis analysis = assembler.accept(frame);
    System.out.println(analysis.status());

    analysis.decodedBlock().ifPresent(block -> {
        System.out.println("stream=" + block.streamId());
        System.out.println("quality=" + block.analysisStatus());
        System.out.println("context=" + block.contextCompleteness());
        System.out.println("omitted=" + block.omittedFieldCount());

        block.method().ifPresent(value -> System.out.println("method=" + value));
        block.status().ifPresent(value -> System.out.println("status=" + value));
        block.headerFields().first("content-type").ifPresent(field ->
                System.out.println("content-type=" + field.valueUtf8()));
    });
});
```

Feed all observed frames in a direction to the assembler in observation order.
Non-header frames let it identify and recover from interrupted CONTINUATION
sequences. `HpackFrameAnalysisStatus` distinguishes ignored frames, blocks in
progress, decoded blocks, and discarded blocks. Recovery is unconditional and
the assembler never enters a terminal error state.

If capture is known to begin at the connection start, use
`HpackFrameAssembler.atConnectionStart()`. This reports
`OBSERVED_COMPLETE` until evidence proves otherwise. It means complete relative
to all bytes supplied by the caller, not proof that the capture has no silent
loss.

## Interpret provenance

Every `HpackHeaderField` independently records how its name and value were
obtained:

```java
HpackFieldProvenance provenance = field.provenance();
System.out.println(provenance.nameSource());
System.out.println(provenance.valueSource());
provenance.optionalTableIndex().ifPresent(index ->
        System.out.println("HPACK index=" + index));
```

Sources are `LITERAL`, `STATIC_TABLE`, or `DYNAMIC_TABLE`. Always retain this
provenance in analysis output: a field resolved from the dynamic table depends
on earlier observations even when no missing data was detectable. Header name
lookup is ASCII case-insensitive; `first`, `all`, and `contains` avoid callers
having to repeatedly convert and scan names.

## Diagnostics and best-effort output

Malformed HPACK input, missing dynamic indexes, uncertain context, and assembly
sequence problems are delivered to the configured `HpackDiagnosticSink`.
Results still contain safely decoded fields, an `INCOMPLETE` status, and the
number of omitted fields where known.

The sink is called synchronously during analysis. The default sink does nothing
and diagnostics are not stored internally, so callers can choose to collect,
log, or stream them. Keep the sink non-throwing; an exception from it propagates
after uncertain decoder state is invalidated.

The following example supplies a valid HEADERS frame whose HPACK field block
contains invalid Huffman padding. The frame itself is observed successfully,
but header analysis is incomplete:

```java
List<HpackDiagnostic> diagnostics = new ArrayList<>();
HpackFrameAssembler assembler = HpackFrameAssembler.atConnectionStart(
        HpackDecoderConfig.defaults(), diagnostics::add);

byte[] malformedBytes = HexFormat.of().parseHex(
        "0000040104000000010081ff00");
Http2Frame malformedFrame = parser.observe(malformedBytes)
        .frame().orElseThrow();

HpackFrameAnalysis failure = assembler.accept(malformedFrame);
DecodedHeaderBlock partial = failure.decodedBlock().orElseThrow();

System.out.println(failure.status());             // BLOCK_ANALYZED
System.out.println(partial.analysisStatus());     // INCOMPLETE
System.out.println(partial.contextCompleteness());// PARTIAL
diagnostics.forEach(System.out::println);         // MALFORMED_BLOCK, then context change
```

`BLOCK_ANALYZED` means that the complete encoded block reached the decoder; use
the decoded block's `analysisStatus()` to determine whether all of it was
decoded. Fields decoded safely before the fault remain available.

Malformed wire content does not make the assembler unusable. Uncertain dynamic
table reconstruction is cleared, so a later block can still be processed:

```java
byte[] followingBytes = HexFormat.of().parseHex(
        "00000101040000000382"); // indexed static-table field: :method GET
Http2Frame followingFrame = parser.observe(followingBytes)
        .frame().orElseThrow();

DecodedHeaderBlock following = assembler.accept(followingFrame)
        .decodedBlock().orElseThrow();

System.out.println(following.analysisStatus()); // COMPLETE
System.out.println(following.method().orElseThrow()); // GET
```

Truncated HPACK strings, invalid Huffman encodings, invalid integer encodings,
and misplaced or excessive dynamic-table size updates follow this same
best-effort path. They produce an incomplete result and diagnostics rather than
terminating subsequent analysis. Later static-table and literal fields can be
decoded normally. Dynamic-table references may be omitted until observed
insertions rebuild enough local state.

A missing indexed name for an incremental literal also invalidates local
dynamic-table reconstruction to prevent shifted indexes from producing false
mappings; later observed inserts rebuild context.

Direct `HpackDecoder.analyze` is available for integrations that already have
a complete HPACK field block. Normal frame processing should use the assembler,
which owns its decoder and its cross-frame state.

See the [advanced guide](advanced.md) for resource limits, SETTINGS, snapshots,
and detailed recovery behavior.
