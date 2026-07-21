package dev.darcro.http2.extract;

import dev.darcro.http2.frame.Http2FrameObservation;
import java.util.Objects;

/** A frame extracted at a synchronized payload-stream boundary. */
public record Http2FrameExtracted(long streamOffset,
                                  Http2FrameObservation observation,
                                  FrameBoundaryProvenance boundaryProvenance)
        implements Http2ExtractionEvent {
    public Http2FrameExtracted {
        if (streamOffset < 0) {
            throw new IllegalArgumentException("streamOffset must be non-negative");
        }
        Objects.requireNonNull(observation, "observation");
        Objects.requireNonNull(boundaryProvenance, "boundaryProvenance");
        if (!observation.valid()) {
            throw new IllegalArgumentException("extracted frame observation must be valid");
        }
    }
}
