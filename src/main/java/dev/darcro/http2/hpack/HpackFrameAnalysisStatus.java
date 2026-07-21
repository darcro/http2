package dev.darcro.http2.hpack;

/**
 * Assembly outcome from supplying one HTTP/2 frame to an HPACK frame
 * assembler. This status describes frame-block assembly, not whether HPACK
 * decoding was complete or diagnostic-free.
 */
public enum HpackFrameAnalysisStatus {
    /**
     * Neutral outcome: the accepted frame does not contribute to an HPACK
     * field block and no block was completed or discarded by that frame.
     */
    IGNORED,

    /**
     * Normal in-progress outcome: a HEADERS or PUSH_PROMISE block has started
     * and requires one or more CONTINUATION frames.
     */
    AWAITING_CONTINUATION,

    /**
     * A complete encoded field block reached the decoder and produced a
     * decoded-block result. This does not imply complete decoding; inspect the
     * decoded block's analysis status and this result's diagnostics.
     */
    BLOCK_ANALYZED,

    /**
     * Recovery outcome: an incomplete, out-of-sequence, or oversized field
     * block was abandoned because it could not be analyzed safely.
     */
    BLOCK_DISCARDED
}
