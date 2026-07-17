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
}
