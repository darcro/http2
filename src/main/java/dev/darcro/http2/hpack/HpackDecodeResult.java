package dev.darcro.http2.hpack;

import java.util.List;
import java.util.Objects;

/** A decoded HPACK field block plus any best-effort recovery events. */
public record HpackDecodeResult(HpackHeaderFields fields,
                                List<HpackRecoveryEvent> recoveryEvents) {
    public HpackDecodeResult {
        Objects.requireNonNull(fields, "fields");
        Objects.requireNonNull(recoveryEvents, "recoveryEvents");
        recoveryEvents = List.copyOf(recoveryEvents);
    }

    /** Returns whether any field representation was skipped during recovery. */
    public boolean recovered() {
        return !recoveryEvents.isEmpty();
    }
}
