# Advanced Configuration Guide

This guide describes parser limits, HPACK resource controls, connection state,
and failure handling. The defaults favor offline capture analysis where the
start of the HTTP/2 connection and its SETTINGS exchange may be missing. A
complete HTTP/2 endpoint should use explicit strict limits and integrate
negotiated settings deliberately.

Frame types are in `dev.darcro.http2.frame`; HPACK types are in
`dev.darcro.http2.hpack`. Both packages are delivered by the same artifact.

## Frame parser configuration

The default parser accepts payloads up to 16,777,215 bytes, the maximum payload
length encodable by HTTP/2. This avoids rejecting captured frames only because
the earlier `SETTINGS_MAX_FRAME_SIZE` exchange was missed:

```java
Http2FrameParser parser = new Http2FrameParser();
```

For endpoint-style strictness before a larger setting has been negotiated, pass
the initial HTTP/2 limit explicitly:

```java
Http2FrameParser parser = new Http2FrameParser(
        Http2FrameParser.INITIAL_MAX_FRAME_SIZE);
```

The configured value must be between 16,384 and 16,777,215. The parser remains
stateless and thread-safe after construction. It validates frame-local
structure, but it does not validate stream lifecycle, flow-control state,
CONTINUATION ordering, endpoint role, or connection-level SETTINGS state.

For a frame stored within a larger array, use the range overload without
copying:

```java
Http2Frame frame = parser.parse(buffer, frameOffset, exactFrameLength);
```

The selected range must contain exactly one frame. Truncated input and trailing
bytes are rejected.

## Frame parsing errors

`ParseErrorException` provides:

- `reason()` — a `ParseErrorReason` category.
- `offset()` — byte offset relative to the supplied frame range.
- `frameType()` — the wire type when enough header bytes were available.

The reason categories distinguish truncated headers, length mismatches, size
errors, invalid stream identifiers, invalid payloads, padding, and SETTINGS
values. Invalid method arguments instead use standard Java exceptions such as
`NullPointerException` and `IndexOutOfBoundsException`.

## HPACK resource limits

Default HPACK limits are intentionally bounded but capture-oriented:

| Setting | Default | Meaning |
| --- | ---: | --- |
| `maxDynamicTableCapacity` | 16,777,215 | Hard upper bound accepted by table-size updates and `updateMaxDynamicTableSize` |
| `maxEncodedHeaderBlockSize` | 16,777,215 | Maximum compressed bytes in one complete field block |
| `maxDecodedHeaderListSize` | 16,777,215 | Maximum decoded list size, including 32 bytes overhead per field |
| `dynamicTableRecoveryPolicy` | `SKIP_MISSING` | Handling for dynamic-table indexes missing from the local capture context |

Override them when creating an assembler or a lower-level decoder:

```java
HpackDecoderConfig config = new HpackDecoderConfig(
        16_384,   // hard dynamic-table capacity
        131_072,  // encoded block limit
        262_144); // decoded header-list limit

HpackFrameAssembler assembler = new HpackFrameAssembler(config);
HpackDecoder decoder = new HpackDecoder(config); // complete HPACK blocks only
```

The dynamic-table capacity must be at least 4,096 because that is the initial
RFC 7541 table-size limit. Both block and header-list limits must be positive.
The decoded header-list size is calculated as `name length + value length + 32`
for each field, using decoded rather than Huffman-encoded lengths.

`HpackDecoderConfig.DEFAULT_CONFIG` is the shared immutable default instance;
`HpackDecoderConfig.defaults()` returns that same instance.

The default limits and recovery policy are intended for captured traffic
analysis where decoding may begin after an HTTP/2 connection is already in
progress and the SETTINGS frames may be absent. Use `strictDefaults()` for
endpoint-style behavior close to the original bounded defaults:

```java
HpackDecoderConfig strictConfig = HpackDecoderConfig.strictDefaults();
```

Increasing limits increases the memory and CPU available to untrusted peers.
Set them from application policy rather than directly from peer-provided
values.

## Dynamic table and SETTINGS

An assembler's owned decoder starts with an empty dynamic table and an active
table limit of 4,096 bytes, but the default configuration allows later
table-size updates up to 16,777,215 bytes when the SETTINGS exchange was
missed. Use one assembler for each inbound compression context. A tool
observing both connection directions therefore needs independent assemblers for
requests and responses.

When a locally advertised `SETTINGS_HEADER_TABLE_SIZE` change has taken effect
according to the HTTP/2 SETTINGS acknowledgment rules, update the assembler:

```java
assembler.updateMaxDynamicTableSize(appliedHeaderTableSize);
```

Lower-level users decoding complete HPACK blocks call the method with the same
name on their `HpackDecoder`.

The value cannot exceed `config.maxDynamicTableCapacity()`. After an explicit
reduction, the next encoded block must begin with a conforming HPACK table-size
update. The decoder tracks the smallest pending reduction when settings change
more than once between field blocks.

`dynamicTableSize()` reports the bytes currently occupied by entries.
`maxDynamicTableSize()` reports the current allowed protocol maximum. With the
capture-oriented defaults this initially equals the configured capacity, not
the active 4,096-byte dynamic table limit.

Neither API accepts `SettingsFrame` directly. Connection code remains
responsible for endpoint direction, acknowledgment timing, and choosing which
peer setting applies to which compression context.

## Stateful decoding and assembly

`HpackDecoder` and `HpackFrameAssembler` are not thread-safe. Keep them confined
to the connection event loop or protect access externally. The assembler owns
its decoder exclusively and exposes configuration, dynamic-table metrics, and
SETTINGS updates without exposing the live decoder.

The assembler accepts every `Http2Frame`:

- While idle, non-header frames are ignored.
- HEADERS or PUSH_PROMISE starts a field block.
- CONTINUATION fragments must be contiguous and use the same stream ID.
- Completion returns `DecodedHeaderBlock` when `END_HEADERS` is observed.

The default sequence policy is `FAIL_FAST`, which matches HTTP/2 connection
semantics. Capture-analysis tools can opt into
`HpackFrameSequenceRecoveryPolicy.RECOVER`:

```java
HpackFrameAssembler assembler = new HpackFrameAssembler(
        config, HpackFrameSequenceRecoveryPolicy.RECOVER);
```

In recover mode, an orphan CONTINUATION is ignored, a wrong-stream CONTINUATION
abandons the current incomplete block, and an interleaved frame abandons the
current incomplete block before the incoming frame is processed as idle input.
Each recovered sequence error is reported through
`assembler.recoveryEvents()`. Call `clearRecoveryEvents()` after consuming
diagnostics if the application reports them incrementally.

`DecodedHeaderBlock` retains the originating frame kind, stream ID, END_STREAM
state, optional promised stream ID, and ordered decoded fields. Header order and
duplicates are preserved.

For lower-level complete-block decoding, `decode(...)` returns just the fields
and `decodeResult(...)` returns fields plus recovery events:

```java
HpackDecodeResult result = decoder.decodeResult(headerBlock);
if (result.recovered()) {
    result.recoveryEvents().forEach(System.out::println);
}
```

The assembler stores fragment views rather than concatenating them. The source
frame arrays must therefore remain unchanged until the field block completes.

## Persisting offline decoding state

Decoder state can be saved between executions when decoding a captured stream.
The resumed input must be from the same connection direction and begin at the
exact next header-block position. Never restore a snapshot for a new HTTP/2
connection because its compression context will not match.

```java
HpackDecoderSnapshot snapshot = decoder.snapshot();
Files.write(snapshotPath, snapshot.toByteArray());

HpackDecoderSnapshot loaded = HpackDecoderSnapshot.fromByteArray(
        Files.readAllBytes(snapshotPath));
HpackDecoder restored = HpackDecoder.restore(loaded, currentConfig);
```

Snapshot objects expose read-only limits, the pending SETTINGS reduction, and
dynamic entries in newest-first index order. Restore always creates a new
decoder under an explicit `HpackDecoderConfig`; state exceeding those current
limits is rejected.

An assembler snapshot includes its decoder plus any incomplete field block, so
capture can pause between HEADERS/PUSH_PROMISE and CONTINUATION frames:

```java
HpackFrameAssemblerSnapshot snapshot = assembler.snapshot();
byte[] persisted = snapshot.toByteArray();

HpackFrameAssemblerSnapshot loaded =
        HpackFrameAssemblerSnapshot.fromByteArray(persisted);
HpackFrameAssembler restored =
        HpackFrameAssembler.restore(loaded, currentConfig);
```

Incomplete fragments are copied into the snapshot. Snapshot calls are valid
only on healthy objects and between synchronous `decode` or `accept` calls.
Assembler sequence recovery events are not part of the snapshot format. The
two-argument restore method creates a strict assembler around the restored
decoder state. Use the three-argument overload to resume capture analysis with
sequence recovery enabled:

```java
HpackFrameAssembler restored = HpackFrameAssembler.restore(
        loaded, currentConfig, HpackFrameSequenceRecoveryPolicy.RECOVER);
```

The version 1 binary format uses `H2HP` magic, an object kind, reserved bytes,
big-endian lengths, and exact end-of-input validation. It has no checksum,
encryption, or authentication. Dynamic entries can contain cookies,
authorization values, and other credentials, so applications must protect both
snapshot confidentiality and integrity.

`HpackSnapshotException` reports malformed, unsupported, or incompatible state
through `HpackSnapshotErrorReason` and a byte offset. Restore-policy failures
use offset `-1` because they are not tied to an encoded byte.

## HPACK values and sensitive fields

RFC 7541 defines names and values as opaque octets. `HpackHeaderField` exposes
them as `ByteSequence` values. `nameUtf8()` and `valueUtf8()` are convenience
conversions and allocate strings, but decode the underlying range without an
intermediate byte-array copy. Use the byte accessors when exact octets or lower
allocation rates matter.

`DecodedHeaderBlock` caches UTF-8 values for `:method`, `:scheme`, `:authority`,
`:path`, and `:status`. If malformed input repeats one of these pseudo-fields,
the convenience accessor returns the first wire-order value and
`hasDuplicatePseudoHeaders()` returns true. This is diagnostic information,
not semantic validation.

`HpackHeaderFields` remains an immutable ordered list. Its `first`, `all`, and
`contains` methods compare names directly using ASCII case-insensitive rules;
they do not allocate normalized names or construct a per-block map. A first or
contains lookup is a linear scan, while `all` allocates an immutable result
list when matches exist. Direct decoder users can wrap their list with
`HpackHeaderFields.copyOf`.

`sensitive()` is true when the peer used HPACK's never-indexed literal form.
Intermediaries that later add encoding support must preserve that property.

The decoder and lookup view do not enforce HTTP/2 message semantics such as
lowercase field names, pseudo-header ordering or uniqueness, forbidden
connection fields, or request/response requirements. Those checks belong in a
higher-level HTTP/2 message validator.

## Failure handling

`HpackDecodingException` exposes a reason, byte offset within the compressed
block, and an optional stream ID when decoding was initiated by the assembler.
Reasons include invalid indexes, malformed Huffman data, truncated input,
integer overflow, illegal table updates, and configured resource limits.

By default, unavailable dynamic-table references are not treated as fatal
because they commonly occur when analyzing captures that start mid-connection.
An indexed field with a missing dynamic entry is skipped. A literal field whose
name references a missing dynamic entry consumes its value to keep block
alignment, skips the field, and does not insert it into the dynamic table.
These lossy decisions are reported as `HpackRecoveryEvent` values on
`HpackDecodeResult` or `DecodedHeaderBlock`.

All other HPACK decoding failures can leave dynamic-table state partially
changed and are connection-fatal in HTTP/2. The decoder is therefore poisoned
after those failures. Discard the connection and decoder; subsequent decode
calls return `DECODER_FAILED`. Configure
`HpackDynamicTableRecoveryPolicy.FAIL_ON_MISSING` when missing dynamic indexes
should also follow this strict failure path.

The parser and HPACK decoder cannot reconstruct missed SETTINGS values. The
default response is therefore permissive but still bounded: accept protocol-size
frames and larger HPACK blocks/table updates, report recoverable missing
dynamic indexes, and reserve exceptions for malformed data or explicit caller
limits.

`HpackFrameSequenceException` reports invalid CONTINUATION sequencing and its
stream ID. With the default `FAIL_FAST` sequence policy, a sequence error
poisons the assembler because it represents a connection protocol error, but it
does not mutate the underlying decoder.

With `HpackFrameSequenceRecoveryPolicy.RECOVER`, the assembler records
`HpackFrameSequenceRecoveryEvent` diagnostics and continues after sequence
errors that occur before HPACK decoding. Recovery never decodes abandoned
incomplete fragments, so the decoder dynamic table is not advanced by skipped
wire data. HPACK decoding failures remain fatal even in recover mode.

Do not attempt to recover poisoned objects with reflection or retained internal
state. Create new instances for a new connection or restore from a known-good
snapshot.

## Deliberate boundaries

The library does not provide:

- TCP or TLS record extraction.
- Parsing multiple frames from a streaming buffer.
- HTTP/2 connection or stream state machines.
- Flow-control accounting.
- HTTP message semantic validation.
- HPACK encoding.

These layers can be built around the frame parser and HPACK decoder without
changing their existing execution paths.
