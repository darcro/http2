package dev.darcro.http2.extract;

import java.util.Objects;

/** Diagnostic for a payload-stream range that could not be extracted. */
public record Http2ExtractionDiagnostic(Http2ExtractionDiagnosticReason reason,
                                        long streamOffset, long byteCount,
                                        String message)
        implements Http2ExtractionEvent {
    public Http2ExtractionDiagnostic {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(message, "message");
        if (streamOffset < 0) {
            throw new IllegalArgumentException("streamOffset must be non-negative");
        }
        if (byteCount < 0) {
            throw new IllegalArgumentException("byteCount must be non-negative");
        }
    }
}
