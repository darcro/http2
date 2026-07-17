package dev.darcro.http2.hpack;

import java.util.Objects;
import java.util.Optional;

/** Passive-analysis outcome for one frame accepted by an assembler. */
public record HpackFrameAnalysis(HpackFrameAnalysisStatus status,
                                 Optional<DecodedHeaderBlock> decodedBlock,
                                 HpackContextCompleteness contextCompleteness) {
    public HpackFrameAnalysis {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(decodedBlock, "decodedBlock");
        Objects.requireNonNull(contextCompleteness, "contextCompleteness");
    }
}
