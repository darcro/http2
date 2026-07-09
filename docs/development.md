# Developer Guide

This guide is for maintainers changing the parser, HPACK implementation, public
API, tests, or release metadata.

## Build and repository layout

The project targets Java 17 and uses standard Maven layout:

```text
src/main/java/dev/darcro/http2/frame/   Frame and parser code
src/main/java/dev/darcro/http2/hpack/   HPACK and frame-assembly code
src/test/java/dev/darcro/http2/         Package-aligned JUnit 5 tests
docs/                             User and maintainer documentation
pom.xml                           Build and artifact metadata
```

Run the complete verification before committing:

```shell
mvn clean verify
git diff --check
```

The project intentionally has no runtime dependencies. JUnit is test-scoped.
Avoid introducing a runtime library for operations that can remain small and
protocol-specific.

GitHub Actions runs the same Maven verification for pull requests and branch
pushes. Version tags matching `v*` also publish the artifact to GitHub
Packages.

## Architecture

The code has three independent layers:

1. `Http2FrameParser` parses exactly one frame and returns the sealed
   `Http2Frame` hierarchy. It performs no connection-state mutation.
2. `HpackDecoder` consumes a complete compressed field block and owns one HPACK
   dynamic-table context.
3. `HpackFrameAssembler` exclusively owns an `HpackDecoder`, observes every
   inbound frame, validates CONTINUATION sequencing, and decodes complete
   blocks.

Immutable `HpackDecoderSnapshot` and `HpackFrameAssemblerSnapshot` values form a
persistence boundary for offline capture continuation. Restoration always
creates new runtime objects under caller-supplied configuration.

`Http2ConnectionPreface` is a separate stateless validator because the client
preface is not an HTTP/2 frame.

The frame package has no dependency on the HPACK package. The HPACK package has
a deliberate one-way dependency on frame byte views and frame models for
`HpackFrameAssembler`. Keep this direction intact: frame parsing must not
implicitly decode HPACK or modify a connection-scoped compression table.

## Frame parser invariants

- A parse call consumes exactly the supplied array or array range.
- The declared 24-bit payload length must equal the available payload bytes.
- The configured maximum is checked before payload interpretation. The default
  maximum is the protocol ceiling for capture analysis; strict endpoint tests
  must pass the initial limit explicitly.
- Payloads use `ByteSequence` views; parsing should not copy application data,
  debug data, opaque PING data, or header fragments.
- The reserved stream-ID bit and unused flags are ignored on receipt while raw
  flags are retained.
- Frame-local sizes, padding, stream-ID rules, and SETTINGS values are strict.
- Unknown frame types and extensible numeric values are preserved.
- Connection-state checks stay outside the parser.

When adding an RFC-defined frame type:

1. Add its numeric constant to `Http2FrameTypes`.
2. Add a public immutable frame record.
3. Permit that record in `Http2Frame`.
4. Add a parser branch with frame-local validation only.
5. Add valid, boundary, malformed, and unknown-flag tests.
6. Update the frame table in the simple usage guide.

## HPACK decoder invariants

`HpackDecoder` implements RFC 7541 and is stateful by design:

- Static table indexes are 1 through 61.
- Dynamic index 62 is the newest entry.
- Dynamic entry size is `name bytes + value bytes + 32`.
- Inserting an oversized entry clears the table without adding it.
- Table-size updates are accepted only at the beginning of a block, at most
  twice, and may not exceed the applied protocol maximum.
- Default HPACK configuration is capture-oriented and permits larger resource
  ceilings when SETTINGS frames were missed. Use `strictDefaults()` when a test
  or integration requires endpoint-style initial limits.
- A pending SETTINGS reduction requires the next block to begin with an update
  at or below the smallest pending value.
- Header order and duplicates are preserved.
- Never-indexed literals set `HpackHeaderField.sensitive()`.
- Decoded literal bytes are owned by the decoder output and do not alias the
  caller's compressed input.
- Missing dynamic-table references are recoverable by default for capture
  analysis. Indexed fields are skipped; literals with missing indexed names
  still consume their values, are not emitted, and are not inserted.
- Any non-recoverable decoding error permanently poisons the decoder.

The internal `HpackTables` class contains the RFC static table. `HpackHuffman`
contains all 257 Appendix B codes, including EOS, and constructs an immutable
decode trie during class initialization. Huffman decoding must reject:

- The EOS symbol in encoded content.
- Padding longer than seven bits.
- Padding that is not a prefix of EOS.
- Output exceeding the configured header-list budget.

Do not generate or alter these constants from memory. Compare changes against
RFC 7541 Appendix A or B and retain RFC-vector coverage.

## Snapshot format and invariants

`HpackSnapshotCodec` owns the dependency-free version 1 binary format. Do not
use Java object serialization for persisted protocol state. The header contains
`H2HP` magic, version, decoder/assembler kind, zeroed reserved bytes, and a
big-endian payload length.

Decoder payloads contain the encoder-selected table limit, applied protocol
maximum, pending minimum reduction, and newest-first dynamic entries. Assembler
payloads append active state, origin, stream metadata, and copied incomplete
block bytes.

Snapshot decoding must validate before constructing restorable state:

- Every length is non-negative, overflow-safe, and within the supplied bytes;
  trailing bytes are errors.
- Dynamic entry size uses `name + value + 32` with aggregate `long` arithmetic.
- Entries fit the encoded table limit.
- The encoded table limit can exceed the protocol maximum only while a table
  reduction is pending.
- Inactive assemblers have no metadata or fragment bytes.
- Active HEADERS and PUSH_PROMISE state obey stream and promised-stream rules.
- Restore rechecks state against the caller's current resource configuration.

Snapshot objects own copies of dynamic entries and incomplete fragments. Keep
their constructors non-public so callers cannot bypass validation. The format
has no checksum or cryptographic protection; applications own secure storage.
Future formats must use a new version and retain version 1 decoding or reject it
explicitly as unsupported.

## Resource and error behavior

Resource limits are part of the security model, not convenience settings.
Enforce encoded-block limits before decoding and decoded-list limits while
allocating names and values. Use `long` for aggregate arithmetic that can
overflow an `int` before applying configured bounds.

Wire-format failures use checked exceptions with stable reason enums:

- `ParseErrorException` for individual HTTP/2 frames.
- `HpackDecodingException` for compressed data and resource failures.
- `HpackFrameSequenceException` for invalid field-block frame sequences.

Use standard unchecked Java exceptions only for caller programming errors such
as null references, invalid array ranges, or invalid configuration values.

Adding a reason enum value is a public API change. Add a targeted test and
document when callers should expect it.

`HpackRecoveryEvent` is diagnostic rather than exceptional. Use it only when
the representation boundary is known and later bytes can still be decoded
safely. Currently that means unavailable dynamic-table indexes. Do not use
recovery events for truncation, Huffman failures, integer overflow, illegal
table-size updates, or other cases where block alignment is not reliable.

`HpackFrameSequenceRecoveryEvent` is a separate assembler diagnostic for frame
sequence errors that happen before HPACK decoding. It must not be emitted for
malformed HPACK bytes. Recovering a sequence error may discard incomplete
fragments, but it must not call into the decoder for those abandoned fragments.

## Performance and ownership

The frame parser is zero-copy. The HPACK assembler also retains fragment views
instead of concatenating them. Maintain these properties when refactoring.

The assembler must retain exclusive ownership of its decoder. Its public
facade may expose immutable configuration, table-size metrics, and negotiated
limit updates, but must not expose the live decoder or accept one supplied by a
caller. This prevents direct decode calls from mutating the compression context
outside frame order. Keep direct `HpackDecoder` use available for integrations
that already reassemble complete field blocks.

Sequence recovery must preserve that ownership boundary. Ignored orphan
CONTINUATION frames, wrong-stream CONTINUATION frames, and abandoned
interleaved partial blocks are parser/assembler recovery decisions only; they
must not mutate the decoder dynamic table or pending table-size update state.

`HpackHeaderFields` wraps decoded output in an immutable ordered list. It scans
once to cache only the five request/response pseudo-field values defined by RFC
9113 Sections 8.3.1 and 8.3.2. Arbitrary name lookup remains a direct
ASCII-insensitive scan so ordinary decoding does not allocate a map. Keep
lookup separate from HTTP field-semantic validation.

HPACK literal decoding necessarily allocates decoded name and value arrays.
Static and dynamic indexed entries may safely share decoder-owned immutable
arrays. Never expose a mutable backing array through the public API.

Avoid `ByteBuffer` position state in public values. `ByteSequence` provides
stable indexed access, slicing, explicit copying, and content equality.

Before claiming a performance improvement, add a reproducible benchmark or
allocation measurement. Functional tests alone do not establish throughput.

## Test strategy

The current suite contains six groups:

- `Http2FrameParserTest` covers all RFC 9113 frame types, malformed frames,
  array ranges, zero-copy behavior, and supplied real-world frame samples.
- `Http2ConnectionPrefaceTest` covers exact and ranged preface validation.
- `HpackDecoderTest` covers all RFC 7541 Appendix C request/response sequences,
  static and dynamic tables, Huffman decoding, malformed data, configured
  limits, strict and best-effort missing-index behavior, and the captured
  nghttpx response supplied for this project.
- `HpackFrameAssemblerTest` covers parser integration, HEADERS and PUSH_PROMISE
  metadata, CONTINUATION sequencing, interleaving, resource errors, and failed
  states, including exclusive decoder ownership, missing-index recovery,
  opt-in sequence recovery, and its safe facade.
- `HpackHeaderFieldsTest` covers pseudo-field caching, duplicate detection,
  case-insensitive lookup, ordering, immutability, and ranged string decoding.
- `HpackSnapshotTest` covers deterministic binary round trips, dynamic context
  continuation, pending SETTINGS changes, mid-block HEADERS/PUSH_PROMISE state,
  caller limits, immutability, corruption, failed-state rejection, and the fact
  that assembler sequence recovery diagnostics are not persisted.

Protocol changes require both positive and negative tests. For HPACK stateful
behavior, use multiple blocks on the same decoder so dynamic indexes and
evictions are exercised. For captured data, retain the original wire bytes and
assert independently verified decoded values rather than decoder-generated
expectations.

## Compatibility checklist

Before changing a public record, method, exception, or enum:

1. Inspect its compiled public surface with `javap -public`.
2. Prefer additive changes; record component changes alter constructors and
   accessors.
3. Keep existing frame-parser behavior independent from optional HPACK use.
4. Run all existing tests, not only the affected test class.
5. Update the simple and advanced guides where behavior is user-visible.

## Publishing a release artifact

GitHub Packages publishing is driven by version tags. The repository keeps
`pom.xml` at a snapshot version such as `0.1.0-SNAPSHOT`; the workflow derives
the published release version from the tag inside the GitHub Actions runner.
Do not manually change the POM to a release version for GitHub Packages
publishing.

Before publishing:

1. Ensure the working tree is clean.
2. Run local verification:

   ```shell
   mvn clean verify
   git diff --check
   ```

3. Ensure the commit to release has been pushed to GitHub and the branch build
   is green in the Actions tab.
4. Confirm repository Actions permissions allow the workflow `GITHUB_TOKEN` to
   write packages. The workflow declares `packages: write` and does not require
   a custom secret for GitHub Packages.

Create and push an annotated version tag:

```shell
git tag -a v0.1.0 -m "Release 0.1.0"
git push origin v0.1.0
```

Tag names must start with `v` and must not include `-SNAPSHOT`. A tag named
`v0.1.0` publishes Maven artifact
`dev.darcro.http2:http2-parser:0.1.0`.

After pushing the tag:

1. Open the repository's GitHub Actions tab.
2. Find the Maven workflow run for the tag.
3. Confirm the `Build` job and `Publish to GitHub Packages` job both complete.
4. Confirm the package appears under the repository or account Packages page.
5. If publishing a public package, confirm package visibility and access are
   configured as intended.

Consumers use the GitHub Packages repository URL:

```xml
<repository>
    <id>github</id>
    <url>https://maven.pkg.github.com/darcro/http2</url>
</repository>
```

GitHub Packages may require Maven credentials depending on package visibility.
Consumers of private packages need a token with package read access.

If a release fails before deployment, rerun the workflow after fixing the
problem. If it fails after the artifact was deployed, do not reuse the same
version unless the package version is intentionally deleted first; prefer a new
patch version and tag.

Maven Central publishing, source/Javadoc artifacts, signing, and release notes
are out of scope for the current GitHub Packages workflow and should be handled
as a separate release task.

## Reference specifications

- [RFC 9113 — HTTP/2](https://www.rfc-editor.org/rfc/rfc9113.html)
- [RFC 7541 — HPACK](https://www.rfc-editor.org/rfc/rfc7541.html)

When specification text and existing behavior disagree, treat the RFC as the
source of truth, add a regression test, and describe any compatibility impact
in the commit message.
