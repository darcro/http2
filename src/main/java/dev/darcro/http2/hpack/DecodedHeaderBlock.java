package dev.darcro.http2.hpack;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** A decoded field block plus metadata from its originating HTTP/2 frame. */
public record DecodedHeaderBlock(HeaderBlockOrigin origin, int streamId,
                                 boolean endStream, OptionalInt promisedStreamId,
                                 List<HpackHeaderField> fields,
                                 List<HpackRecoveryEvent> recoveryEvents) {
    public DecodedHeaderBlock(HeaderBlockOrigin origin, int streamId,
                              boolean endStream, OptionalInt promisedStreamId,
                              List<HpackHeaderField> fields) {
        this(origin, streamId, endStream, promisedStreamId, fields, List.of());
    }

    public DecodedHeaderBlock {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(promisedStreamId, "promisedStreamId");
        fields = HpackHeaderFields.copyOf(fields);
        recoveryEvents = List.copyOf(recoveryEvents);
    }

    /** Returns the ordered fields with efficient name lookup operations. */
    public HpackHeaderFields headerFields() {
        return (HpackHeaderFields) fields;
    }

    /** Returns the first :method value, if present. */
    public Optional<String> method() {
        return headerFields().method();
    }

    /** Returns the first :scheme value, if present. */
    public Optional<String> scheme() {
        return headerFields().scheme();
    }

    /** Returns the first :authority value, if present. */
    public Optional<String> authority() {
        return headerFields().authority();
    }

    /** Returns the first :path value, if present. */
    public Optional<String> path() {
        return headerFields().path();
    }

    /** Returns the first :status value, if present. */
    public Optional<String> status() {
        return headerFields().status();
    }

    /** Returns whether any recognized pseudo-field occurred more than once. */
    public boolean hasDuplicatePseudoHeaders() {
        return headerFields().hasDuplicatePseudoHeaders();
    }

    /** Returns whether best-effort recovery skipped any unavailable entries. */
    public boolean recovered() {
        return !recoveryEvents.isEmpty();
    }
}
