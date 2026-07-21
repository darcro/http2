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

    /** Returns a concise representation suitable for application logs. */
    @Override
    public String toString() {
        StringBuilder result = new StringBuilder(reason.name());
        if (offset >= 0 || index >= 0 || streamId >= 0) {
            result.append(" [");
            boolean separator = false;
            if (offset >= 0) {
                result.append("offset=").append(offset);
                separator = true;
            }
            if (index >= 0) {
                if (separator) {
                    result.append(", ");
                }
                result.append("index=").append(index);
                separator = true;
            }
            if (streamId >= 0) {
                if (separator) {
                    result.append(", ");
                }
                result.append("streamId=").append(streamId);
            }
            result.append(']');
        }
        return result.append(": ").append(message).toString();
    }
}
