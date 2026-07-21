package dev.darcro.http2.extract;

import dev.darcro.http2.frame.Http2FrameObservation;
import java.util.Objects;

/** A malformed candidate encountered after synchronization was established. */
public record Http2FrameCandidateRejected(long streamOffset,
                                          Http2FrameObservation observation)
        implements Http2ExtractionEvent {
    public Http2FrameCandidateRejected {
        if (streamOffset < 0) {
            throw new IllegalArgumentException("streamOffset must be non-negative");
        }
        Objects.requireNonNull(observation, "observation");
        if (observation.valid()) {
            throw new IllegalArgumentException("rejected observation must be invalid");
        }
    }
}
