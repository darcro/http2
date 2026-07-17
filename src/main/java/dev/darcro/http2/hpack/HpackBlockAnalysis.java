package dev.darcro.http2.hpack;

import java.util.Objects;

/** Result of analyzing one complete encoded HPACK field-block input. */
public record HpackBlockAnalysis(HpackHeaderFields fields, HpackBlockStatus status,
                                 int omittedFieldCount,
                                 HpackContextCompleteness contextCompleteness) {
    public HpackBlockAnalysis {
        Objects.requireNonNull(fields, "fields");
        Objects.requireNonNull(status, "status");
        Objects.requireNonNull(contextCompleteness, "contextCompleteness");
        if (omittedFieldCount < 0) {
            throw new IllegalArgumentException("omittedFieldCount must be non-negative");
        }
    }

    public boolean complete() {
        return status == HpackBlockStatus.COMPLETE;
    }
}
