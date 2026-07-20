# Developer Guide

## Project scope

This Java 17 project is a passive HTTP/2 analysis library. Its primary design
goal is to preserve useful evidence and continue after damaged, incomplete, or
mid-connection captures. It is not an endpoint protocol stack. Do not add TCP
reassembly, TLS handling, direction tracking, response generation, or
connection-fatal endpoint policy to the core library.

Production code has no external dependencies. Frame code is in
`dev.darcro.http2.frame`; stateful HPACK code is in
`dev.darcro.http2.hpack`.

## Architecture and invariants

### Frame layer

`Http2FrameParser` receives one exact candidate frame. `observe` is the
passive-analysis API and preserves raw bytes, an optional header, an optional
typed frame, and diagnostics. `parse` is the strict convenience API.

Parsing is zero-copy by default. Payload records hold `ByteSequence` views into
the selected input range. `observeOwned` makes exactly one defensive copy and
all derived views share it. New frame implementations must preserve unsigned
wire interpretation, validate only invariants local to that frame, and retain
unknown extension payloads.

The client connection preface is handled by the stateless
`Http2ConnectionPreface`; it is not a synthetic frame type.

### HPACK decoder

`HpackDecoder` owns one direction's dynamic table. Its normal public operation
is non-throwing `analyze`; wire errors become diagnostics and incomplete
results. Constructors default to `PARTIAL`, while `atConnectionStart` opts into
`OBSERVED_COMPLETE` relative to supplied data.

Every emitted field must carry correct, independent name and value provenance.
Never infer that `OBSERVED_COMPLETE` proves absence of unseen capture gaps.
There is no explicit input-gap marker by design.

Missing dynamic references are recoverable omissions. When an unknown indexed
name would create an unknown incremental entry, clear local table
reconstruction so later numeric indexes cannot silently resolve to shifted
entries. Malformed state mutations must similarly invalidate only state whose
accuracy can no longer be defended. No error may permanently poison a decoder.

`HpackDecoderConfig` contains local memory/safety ceilings, not assumed peer
SETTINGS values. Capture-tolerant defaults are deliberately broad.

### Frame assembler

`HpackFrameAssembler` owns its decoder and is the preferred integration API.
It collects HEADERS/PUSH_PROMISE plus CONTINUATION fragments, returns a
per-frame `HpackFrameAnalysis`, and recovers from sequence anomalies without a
policy switch. It must not expose its decoder.

Oversized blocks enter a discard state without retaining further fragments
until a matching boundary is observed. Snapshots must preserve active and
discarding states. A diagnostic listener failure may propagate, but uncertain
decoder state must first be invalidated.

### Snapshot format

Snapshot encoding is implemented centrally by `HpackSnapshotCodec`. Version 1
stores context completeness and table-limit knowledge; assembler encoding also
preserves discard state. Other format versions are rejected.

Changes to the binary format require:

1. Incrementing the format version.
2. Deciding explicitly whether older versions should remain accepted.
3. Strict length, flag, range, and resource validation.
4. Fixed-fixture version and corruption tests.
5. Updates to both user-facing guides.

Do not use Java native serialization for snapshots.

## Error and diagnostic design

Frame observations contain their own immutable diagnostics. HPACK uses
`HpackDiagnosticSink`, avoiding unbounded internal accumulation over long
captures. New diagnostic reasons should identify an analysis fact, not
prescribe endpoint action.

Public passive-analysis methods should return structured outcomes for malformed
wire input. Exceptions remain appropriate for programming errors, invalid local
configuration, corrupt snapshot data, and exceptions deliberately thrown by a
user callback.

## Testing

Run all checks with:

```shell
mvn clean verify
```

Tests are under `src/test/java` and include RFC HPACK examples plus captured
real-world frame data. Changes should cover:

- valid and malformed wire encodings;
- exact boundary and unsigned-value cases;
- zero-copy and owned-copy behavior;
- continuation fragmentation and sequence recovery;
- missing dynamic indexes and provenance;
- decoder reuse after malformed or oversized input;
- context transitions and table reconstruction;
- snapshot round trips, unsupported versions, and corruption; and
- configured resource ceilings.

Prefer fixed byte fixtures for protocol examples. Assert structured status,
diagnostic reason, provenance, and state after recovery rather than only an
exception message.

Before committing, run:

```shell
mvn clean verify
git diff --check
git status --short
```

Review the diff for generated files. Maven `target/` output must not be
committed.

## Adding protocol support

For a new frame or extension:

1. Add or update its wire type constant.
2. Implement an immutable `Http2Frame` representation.
3. Parse with explicit bounds checks and `ByteSequence` views.
4. Preserve unrecognized flags and extension data where possible.
5. Add valid, truncated, oversized, and reserved-bit tests.
6. Update the frame-type list in the usage guide.

For HPACK changes, separately review static/dynamic index arithmetic, integer
overflow, Huffman EOS/padding rules, table-size accounting (`name + value +
32`), decoded-list accounting, provenance, partial-result behavior, and state
after an error.

## Publishing a release

GitHub Actions workflow `.github/workflows/maven.yml` verifies every branch and
pull request. A tag beginning with `v` also runs the publish job. The job strips
the `v`, changes the Maven version for that build, and deploys to the GitHub
Packages repository declared in `pom.xml`.

To publish version `1.2.3`:

1. Ensure `main` contains the intended changes and `mvn clean verify` passes.
2. Confirm documentation and release notes describe API or snapshot changes.
3. Create and push an annotated tag:

   ```shell
   git tag -a v1.2.3 -m "Release 1.2.3"
   git push origin v1.2.3
   ```

4. In GitHub Actions, verify both build and publish jobs succeeded.
5. In the Packages view, verify
   `dev.darcro.http2:http2-parser:1.2.3` is available.
6. Optionally consume it from a clean Maven project using the README setup.

The tag version must be a valid Maven version after removing its leading `v`.
Do not manually edit the committed snapshot version merely for the workflow;
the publish job sets the release version. Publishing uses the workflow's
`packages: write` permission and automatically supplied `GITHUB_TOKEN`.

If publication fails, inspect the Actions log before retagging. Never reuse a
version that was successfully published because repositories may reject or
inconsistently cache replacement artifacts.
