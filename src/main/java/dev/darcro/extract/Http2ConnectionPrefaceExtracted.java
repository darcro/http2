package dev.darcro.extract;

/** Reports an exact HTTP/2 client connection preface. */
public record Http2ConnectionPrefaceExtracted(long streamOffset)
        implements Http2ExtractionEvent {
    public Http2ConnectionPrefaceExtracted {
        if (streamOffset < 0) {
            throw new IllegalArgumentException("streamOffset must be non-negative");
        }
    }
}
