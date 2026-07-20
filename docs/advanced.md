# Advanced Passive-Analysis Guide

## Analysis boundary

The library begins at the HTTP/2 application layer. It intentionally does not
reassemble TCP, decrypt TLS, find frame boundaries in arbitrary byte streams,
correlate the two directions, enforce endpoint protocol actions, or generate
HTTP/2 responses. Those concerns are handled by the capture pipeline.

The frame parser expects an exact candidate frame. The HPACK assembler expects
typed frames for one direction in their observed order. Neither API treats a
diagnostic as a reason to permanently disable the object.

## Frame observation and strict parsing

Use `observe(byte[])` or its range overload for evidence-preserving analysis.
An `Http2FrameObservation` contains the complete raw candidate, the parsed
nine-byte header when available, an optional typed frame, and structured
diagnostics.

`observeOwned` first copies the selected range and makes all returned views
refer to that copy. Plain `observe` and `parse` are zero-copy. `ByteSequence`
is an immutable view, but it cannot prevent the owner of its backing array from
modifying that array.

The strict `parse` overloads throw `ParseErrorException`. They are useful in
pipelines that have already classified malformed candidates and do not need an
observation object.

The default parser permits payloads up to the 24-bit HTTP/2 wire maximum. Use
`new Http2FrameParser(maxFrameSize)` to apply a smaller local resource limit.
This setting is a capture safety limit, not a claim about a missed peer
`SETTINGS_MAX_FRAME_SIZE` value.

## HPACK construction and completeness

The default decoder and assembler begin with
`HpackContextCompleteness.PARTIAL`. This is conservative for a passive capture
that may start mid-connection:

```java
HpackDiagnosticSink sink = diagnostic -> log(diagnostic);
HpackFrameAssembler assembler = new HpackFrameAssembler(
        HpackDecoderConfig.defaults(), sink);
```

When observation definitely begins at the connection start, use:

```java
HpackFrameAssembler assembler = HpackFrameAssembler.atConnectionStart(
        HpackDecoderConfig.defaults(), sink);
```

`OBSERVED_COMPLETE` means that the decoder has a coherent history relative to
everything supplied to it. It cannot prove that an external capture silently
omitted packets. There is deliberately no `markInputGap()` API. Dynamic-table
provenance on every resolved field exposes the dependency on earlier state even
when a gap was unknowable.

## Resource configuration and missed SETTINGS

`HpackDecoderConfig` defines local safety ceilings:

| Property | Default | Purpose |
| --- | ---: | --- |
| `maxDynamicTableCapacity` | 16,777,215 | Maximum table-size setting accepted by analysis |
| `maxEncodedHeaderBlockSize` | 16,777,215 | Maximum retained encoded block |
| `maxDecodedHeaderListSize` | 16,777,215 | Maximum decoded name/value size accounting |

The broad defaults reduce false rejection when the capture missed SETTINGS.
Choose lower values for untrusted bulk analysis according to the memory budget:

```java
HpackDecoderConfig config = new HpackDecoderConfig(
        64 * 1024, 256 * 1024, 512 * 1024);
```

`HpackDecoderConfig.defaults()` returns the shared immutable `DEFAULT_CONFIG`.

If an external connection analyzer observes the applicable SETTINGS value, call
`assembler.updateMaxDynamicTableSize(value)`. This updates the peer-advertised
HPACK table limit within the configured safety ceiling. The assembler does not
infer direction or SETTINGS applicability on its own.

## Best-effort decoding semantics

`HpackFrameAssembler.accept` returns `HpackFrameAnalysis`:

| Status | Meaning |
| --- | --- |
| `IGNORED` | The frame does not carry an HPACK field block |
| `AWAITING_CONTINUATION` | A field block remains incomplete |
| `BLOCK_ANALYZED` | A complete block produced a `DecodedHeaderBlock` |
| `BLOCK_DISCARDED` | Unsafe or oversized in-progress encoded data was abandoned |

A decoded block has `COMPLETE` or `INCOMPLETE` analysis status, an omitted-field
count, and the decoder context completeness at that point. `recovered()` is a
convenience test for incomplete output, omitted fields, or partial context.

Unavailable dynamic indexes are omitted and diagnosed. Other fields that can
be decoded safely are returned. An unavailable indexed name on a literal with
incremental indexing is more dangerous: the unknown inserted entry would shift
all later dynamic indexes. The decoder therefore clears its locally
reconstructed table before continuing; later observed literals can rebuild it.

Malformed encodings and resource-limit violations produce diagnostics and an
incomplete result. The decoder clears state whose correctness can no longer be
established and remains available for later blocks. It never models endpoint
connection-fatal behavior.

The assembler recovers unconditionally from unexpected CONTINUATION,
wrong-stream CONTINUATION, and interleaved frames. It abandons unsafe partial
fragments. If a block exceeds the encoded-size limit, it enters a low-memory
discard state until the matching `END_HEADERS` or another observation makes a
new recovery decision.

## Header provenance and lookup

`HpackFieldProvenance` records independent sources for a field's name and
value. A literal value can have a `STATIC_TABLE` name and a `LITERAL` value. A
fully indexed field has the same static or dynamic source for both.
`optionalTableIndex()` supplies the HPACK index where applicable.

`HpackHeaderFields` stores fields in wire order and builds reusable
ASCII-case-insensitive name indexes. Use `first`, `all`, and `contains` instead
of converting every name to `String` for each search. The cached pseudo-header
accessors on `DecodedHeaderBlock` are `method`, `scheme`, `authority`, `path`,
and `status`.

## Diagnostics

Configure an `HpackDiagnosticSink` when diagnostics must be retained. The
default sink is a no-op to avoid hidden allocation over long captures.
Diagnostic reasons cover missing indexes, malformed blocks, resource limits,
sequence recovery, discarded blocks, and context becoming partial.

The callback runs synchronously. If it throws, the exception is propagated;
the decoder conservatively invalidates uncertain state before allowing it to
escape. Keep callbacks fast and non-throwing in capture pipelines.

Frame parsing diagnostics are stored directly in `Http2FrameObservation`
because they describe one immutable candidate. HPACK diagnostics use a sink
because decoding state spans many frames.

## Direct decoder use

Use `HpackDecoder.analyze` only when the caller already owns complete HPACK
field blocks. It returns `HpackBlockAnalysis` rather than throwing for malformed
wire content. Frame-based integrations should use `HpackFrameAssembler`, which
owns its decoder and prevents divergence between assembly and HPACK state.

## Snapshot and restore

Snapshots allow analysis state to cross process executions:

```java
byte[] encoded = assembler.snapshot().toByteArray();
HpackFrameAssemblerSnapshot snapshot =
        HpackFrameAssemblerSnapshot.fromByteArray(encoded);
HpackFrameAssembler restored = HpackFrameAssembler.restore(
        snapshot, config, sink);
```

Assembler snapshots include decoder state, context completeness, whether the
table limit is known, an incomplete block, or a discard-through-END_HEADERS
state. Restore validates the snapshot against current resource limits.

Snapshots use format version 1; other versions are rejected. Snapshot bytes are
an internal binary interchange format, not Java serialization. Treat captured
header values as sensitive when storing them.

Decoder, assembler, parser, and snapshot objects are not intended for
concurrent mutation. Confine each assembler to the worker processing its
connection direction.
