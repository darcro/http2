package dev.darcro.http2.hpack;

import java.util.Objects;
import java.util.OptionalInt;

/** A synchronous diagnostic produced during passive HPACK analysis. */
public record HpackDiagnostic(HpackDiagnosticReason reason, int offset, int index,
                              int streamId, String message) {
    public HpackDiagnostic {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(message, "message");
    }

    public OptionalInt optionalIndex() {
        return index < 0 ? OptionalInt.empty() : OptionalInt.of(index);
    }

    public OptionalInt optionalStreamId() {
        return streamId < 0 ? OptionalInt.empty() : OptionalInt.of(streamId);
    }

    HpackDiagnostic withStreamId(int value) {
        return new HpackDiagnostic(reason, offset, index, value, message);
    }
}
