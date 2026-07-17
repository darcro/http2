package dev.darcro.http2.frame;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/** Raw captured bytes plus the frame interpretation available from them. */
public record Http2FrameObservation(ByteSequence rawBytes,
                                    Optional<Http2FrameHeader> header,
                                    Optional<Http2Frame> frame,
                                    List<FrameDiagnostic> diagnostics) {
    public Http2FrameObservation {
        Objects.requireNonNull(rawBytes, "rawBytes");
        Objects.requireNonNull(header, "header");
        Objects.requireNonNull(frame, "frame");
        diagnostics = List.copyOf(diagnostics);
    }

    public boolean valid() {
        return frame.isPresent();
    }
}
