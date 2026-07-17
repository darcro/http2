/**
 * Best-effort HPACK analysis for one observed HTTP/2 connection direction,
 * including frame-block assembly, field provenance, diagnostics, lookup, and
 * persistent snapshots.
 *
 * <p>The frame assembler exclusively owns its decoder. Both components recover
 * from incomplete passive-capture input without entering terminal error
 * states.</p>
 */
package dev.darcro.http2.hpack;
