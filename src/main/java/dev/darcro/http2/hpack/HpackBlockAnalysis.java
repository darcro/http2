package dev.darcro.http2.hpack;

import java.util.List;
import java.util.Objects;

/**
 * Result of analyzing one complete encoded HPACK field-block input, including
 * immutable diagnostics produced by that analysis call.
 */
public record HpackBlockAnalysis(HpackHeaderFields fields, HpackBlockStatus status,
                                 int omittedFieldCount,
                                 HpackContextCompleteness contextCompleteness,
                                 List<HpackDiagnostic> diagnostics) {
    public HpackBlockAnalysis {
        Objects.requireNonNull(fields, "fields");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(contextCompleteness, "contextCompleteness");
        diagnostics = List.copyOf(diagnostics);
        if (omittedFieldCount < 0) {
            throw new IllegalArgumentException("omittedFieldCount must be non-negative");
        }
    }

    public boolean complete() {
        return status == HpackBlockStatus.COMPLETE;
    }
}
