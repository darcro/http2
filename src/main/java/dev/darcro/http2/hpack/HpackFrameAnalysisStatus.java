package dev.darcro.http2.hpack;

/** Outcome of supplying one HTTP/2 frame to an HPACK frame assembler. */
public enum HpackFrameAnalysisStatus {
    IGNORED,
    AWAITING_CONTINUATION,
    BLOCK_ANALYZED,
    BLOCK_DISCARDED
}
