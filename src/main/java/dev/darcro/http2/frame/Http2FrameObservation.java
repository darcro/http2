package dev.darcro.http2.frame;

import java.util.Objects;
import java.util.Optional;

/** Raw captured bytes plus the frame interpretation available from them. */
public record Http2FrameObservation(ByteSequence rawBytes,
                                    Optional<Http2FrameHeader> header,
                                    Optional<Http2Frame> frame,
                                    Optional<FrameDiagnostic> diagnostic) {
    public Http2FrameObservation {
        Objects.requireNonNull(rawBytes, "rawBytes");
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(frame, "frame");
        Objects.requireNonNull(diagnostic, "diagnostic");
    }

    public boolean valid() {
        return frame.isPresent();
    }
}
