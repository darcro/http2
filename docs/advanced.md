# Advanced Configuration Guide

This guide describes parser limits, HPACK resource controls, connection state,
and failure handling. The defaults are suitable for small applications, but a
complete HTTP/2 endpoint must integrate negotiated settings deliberately.

Frame types are in `dev.darcro.http2.frame`; HPACK types are in
`dev.darcro.http2.hpack`. Both packages are delivered by the same artifact.

## Frame parser configuration

The default parser accepts payloads up to 16,384 bytes, the initial HTTP/2
maximum:

```java
Http2FrameParser parser = new Http2FrameParser();
```

After the peer has negotiated a larger `SETTINGS_MAX_FRAME_SIZE`, create a
parser with that value:

```java
Http2FrameParser parser = new Http2FrameParser(1_048_576);
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

Default HPACK limits are intentionally bounded:

| Setting | Default | Meaning |
| --- | ---: | --- |
| `maxDynamicTableCapacity` | 4,096 | Hard upper bound accepted by `updateMaxDynamicTableSize` |
| `maxEncodedHeaderBlockSize` | 65,536 | Maximum compressed bytes in one complete field block |
| `maxDecodedHeaderListSize` | 65,536 | Maximum decoded list size, including 32 bytes overhead per field |

Override them when creating the decoder:

```java
HpackDecoderConfig config = new HpackDecoderConfig(
        16_384,   // hard dynamic-table capacity
        131_072,  // encoded block limit
        262_144); // decoded header-list limit

HpackDecoder decoder = new HpackDecoder(config);
```

The dynamic-table capacity must be at least 4,096 because that is the initial
RFC 7541 table-size limit. Both block and header-list limits must be positive.
The decoded header-list size is calculated as `name length + value length + 32`
for each field, using decoded rather than Huffman-encoded lengths.

Increasing limits increases the memory and CPU available to untrusted peers.
Set them from application policy rather than directly from peer-provided
values.

## Dynamic table and SETTINGS

An `HpackDecoder` starts with an empty dynamic table and an allowed size of
4,096 bytes. Use one decoder for each inbound compression context. In a normal
client/server connection this means independent request and response decoders.

When a locally advertised `SETTINGS_HEADER_TABLE_SIZE` change has taken effect
according to the HTTP/2 SETTINGS acknowledgment rules, update the decoder:

```java
decoder.updateMaxDynamicTableSize(appliedHeaderTableSize);
```

The value cannot exceed `config.maxDynamicTableCapacity()`. After a reduction,
the next encoded block must begin with a conforming HPACK table-size update.
The decoder tracks the smallest pending reduction when settings change more
than once between field blocks.

`dynamicTableSize()` reports the bytes currently occupied by entries.
`maxDynamicTableSize()` reports the latest protocol maximum supplied through
`updateMaxDynamicTableSize`; it is not the current number of occupied bytes.

The decoder deliberately does not accept `SettingsFrame` directly. Connection
code remains responsible for endpoint direction, acknowledgment timing, and
choosing which peer setting applies to which compression context.

## Stateful decoding and assembly

`HpackDecoder` and `HpackFrameAssembler` are not thread-safe. Keep them confined
to the connection event loop or protect access externally.

The assembler accepts every `Http2Frame`:

- While idle, non-header frames are ignored.
- HEADERS or PUSH_PROMISE starts a field block.
- CONTINUATION fragments must be contiguous and use the same stream ID.
- Completion returns `DecodedHeaderBlock` when `END_HEADERS` is observed.

`DecodedHeaderBlock` retains the originating frame kind, stream ID, END_STREAM
state, optional promised stream ID, and ordered decoded fields. Header order and
duplicates are preserved.

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
HpackDecoder restoredDecoder = restored.decoder();
```

Incomplete fragments are copied into the snapshot. Snapshot calls are valid
only on healthy objects and between synchronous `decode` or `accept` calls.

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
conversions and allocate strings; use the byte accessors when exact octets or
lower allocation rates matter.

`sensitive()` is true when the peer used HPACK's never-indexed literal form.
Intermediaries that later add encoding support must preserve that property.

The decoder does not enforce HTTP/2 message semantics such as lowercase field
names, pseudo-header ordering, forbidden connection fields, or request/response
requirements. Those checks belong in a higher-level HTTP/2 message validator.

## Failure handling

`HpackDecodingException` exposes a reason, byte offset within the compressed
block, and an optional stream ID when decoding was initiated by the assembler.
Reasons include invalid indexes, malformed Huffman data, truncated input,
integer overflow, illegal table updates, and configured resource limits.

An HPACK decoding failure can leave dynamic-table state partially changed and
is connection-fatal in HTTP/2. The decoder is therefore poisoned after any
decoding failure. Discard the connection and decoder; subsequent decode calls
return `DECODER_FAILED`.

`HpackFrameSequenceException` reports invalid CONTINUATION sequencing and its
stream ID. A sequence error poisons the assembler because it represents a
connection protocol error, but it does not mutate the underlying decoder.

Do not attempt to recover either object with reflection or retained internal
state. Create new instances for a new connection.

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
