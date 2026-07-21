package dev.darcro.http2.hpack;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Passive-analysis outcome for one frame accepted by an assembler, including
 * immutable diagnostics produced while accepting that frame.
 */
public record HpackFrameAnalysis(HpackFrameAnalysisStatus status,
                                 Optional<DecodedHeaderBlock> decodedBlock,
                                 HpackContextCompleteness contextCompleteness,
                                 List<HpackDiagnostic> diagnostics) {
    public HpackFrameAnalysis {
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(decodedBlock, "decodedBlock");
        Objects.requireNonNull(contextCompleteness, "contextCompleteness");
        diagnostics = List.copyOf(diagnostics);
    }
}
