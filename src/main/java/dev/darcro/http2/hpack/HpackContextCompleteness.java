package dev.darcro.http2.hpack;

/** Confidence in the locally reconstructed HPACK dynamic context. */
public enum HpackContextCompleteness {
    /** Complete relative to all bytes supplied since a known table reset. */
    OBSERVED_COMPLETE,

    /** Earlier or skipped compression state might be unavailable. */
    PARTIAL
}
