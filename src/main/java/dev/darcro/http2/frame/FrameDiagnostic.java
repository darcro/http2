package dev.darcro.http2.frame;

import java.util.Objects;
import java.util.OptionalInt;

/** A frame-local problem observed while interpreting captured bytes. */
public record FrameDiagnostic(ParseErrorReason reason, int offset, int frameType,
                              String message) {
    public FrameDiagnostic {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(message, "message");
    }

    public OptionalInt optionalFrameType() {
        return frameType < 0 ? OptionalInt.empty() : OptionalInt.of(frameType);
    }

    /** Returns a concise representation suitable for application logs. */
    @Override
    public String toString() {
        StringBuilder result = new StringBuilder(reason.name());
        if (offset >= 0 || frameType >= 0) {
            result.append(" [");
            if (offset >= 0) {
                result.append("offset=").append(offset);
            }
            if (frameType >= 0) {
                if (offset >= 0) {
                    result.append(", ");
                }
                result.append("frameType=0x");
                if (frameType < 0x10) {
                    result.append('0');
                }
                result.append(Integer.toHexString(frameType));
            }
            result.append(']');
        }
        return result.append(": ").append(message).toString();
    }
}
