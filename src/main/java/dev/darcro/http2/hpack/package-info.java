/**
 * Stateful RFC 7541 HPACK decoding and optional HTTP/2 field-block frame
 * assembly.
 *
 * <p>The core decoder accepts complete compressed blocks. Frame assembly has a
 * one-way dependency on {@link dev.darcro.http2.frame} APIs.</p>
 */
package dev.darcro.http2.hpack;
