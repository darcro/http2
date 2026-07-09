package dev.darcro.http2.hpack;

import java.util.Objects;

/**
 * Diagnostic event emitted when an assembler configured for sequence recovery
 * skips or abandons frames before HPACK decoding.
 */
public record HpackFrameSequenceRecoveryEvent(
        HpackFrameSequenceReason reason,
        int streamId,
        String message) {
    public HpackFrameSequenceRecoveryEvent {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(message, "message");
    }
}
