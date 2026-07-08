package dev.darcro.http2.hpack;

/**
 * Controls how the HPACK decoder handles dynamic-table references that are not
 * available in the local decoding context.
 */
public enum HpackDynamicTableRecoveryPolicy {
    /**
     * Skip only unavailable dynamic-table references and continue decoding
     * later representations in the same block.
     */
    SKIP_MISSING,

    /** Treat unavailable dynamic-table references as fatal HPACK errors. */
    FAIL_ON_MISSING
}
