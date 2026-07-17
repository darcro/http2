package dev.darcro.http2.hpack;

/** Categories emitted while performing passive HPACK analysis. */
public enum HpackDiagnosticReason {
    MISSING_DYNAMIC_TABLE_INDEX,
    MISSING_DYNAMIC_TABLE_NAME_INDEX,
    MALFORMED_BLOCK,
    RESOURCE_LIMIT,
    UNEXPECTED_CONTINUATION,
    WRONG_STREAM_CONTINUATION,
    INTERLEAVED_FRAME,
    BLOCK_DISCARDED,
    CONTEXT_BECAME_PARTIAL
}
