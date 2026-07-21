package dev.darcro.http2.extract;

/**
 * Synchronous receiver for ordered extraction events. Implementations must not
 * call back into the emitting extractor.
 */
@FunctionalInterface
public interface Http2ExtractionEventSink {
    /** Receives one event; runtime exceptions are propagated to the caller. */
    void accept(Http2ExtractionEvent event);
}
