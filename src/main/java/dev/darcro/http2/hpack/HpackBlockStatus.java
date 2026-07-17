package dev.darcro.http2.hpack;

/** Whether the complete encoded field block could be structurally analyzed. */
public enum HpackBlockStatus {
    COMPLETE,
    INCOMPLETE
}
