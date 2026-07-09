package dev.darcro.http2.hpack;

/**
 * Controls how {@link HpackFrameAssembler} handles invalid HTTP/2 field-block
 * frame sequencing before any HPACK decode is attempted.
 */
public enum HpackFrameSequenceRecoveryPolicy {
    /**
     * Treat invalid CONTINUATION sequencing as fatal. This matches HTTP/2
     * connection semantics and is the default behavior.
     */
    FAIL_FAST,

    /**
     * Record invalid CONTINUATION sequencing, discard affected in-progress
     * field-block fragments, and continue accepting later frames.
     */
    RECOVER
}
