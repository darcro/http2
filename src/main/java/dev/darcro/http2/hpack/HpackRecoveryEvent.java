package dev.darcro.http2.hpack;

import java.util.Objects;

/** Describes a lossy best-effort HPACK recovery decision. */
public record HpackRecoveryEvent(HpackRecoveryReason reason, int offset,
                                 int index, String message) {
    public HpackRecoveryEvent {
        Objects.requireNonNull(reason, "reason");
        Objects.requireNonNull(message, "message");
    }
}
