package dev.darcro.http2.hpack;

/** Categories for invalid or incompatible persisted HPACK state. */
public enum HpackSnapshotErrorReason {
    INVALID_MAGIC,
    UNSUPPORTED_VERSION,
    INVALID_KIND,
    TRUNCATED_INPUT,
    TRAILING_DATA,
    INVALID_LENGTH,
    INVALID_DECODER_STATE,
    INVALID_ASSEMBLER_STATE,
    CONFIGURATION_LIMIT
}
