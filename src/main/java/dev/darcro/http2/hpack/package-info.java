/**
 * Stateful RFC 7541 HPACK decoding and optional HTTP/2 field-block frame
 * assembly.
 *
 * <p>The core decoder accepts complete compressed blocks. The frame assembler
 * exclusively owns its decoder and has a one-way dependency on
 * {@link dev.darcro.http2.frame} APIs. Decoder and assembler state can be
 * captured in immutable versioned snapshots for offline continuation of the
 * same compression context. Decoded blocks provide immutable, ordered header
 * lookup with cached request and response pseudo-fields. Unavailable dynamic
 * table references can be skipped and reported for mid-connection capture
 * analysis.</p>
 */
package dev.darcro.http2.hpack;
