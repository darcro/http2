package dev.darcro.http2.hpack;

/** Categories for best-effort HPACK recovery events. */
public enum HpackRecoveryReason {
    /** An indexed field referenced a missing dynamic-table entry. */
    MISSING_DYNAMIC_TABLE_INDEX,

    /** A literal field used a missing dynamic-table entry as its name. */
    MISSING_DYNAMIC_TABLE_NAME_INDEX
}
