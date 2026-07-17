package dev.darcro.http2.hpack;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalInt;

/** A decoded field block plus metadata from its originating HTTP/2 frame. */
public record DecodedHeaderBlock(HeaderBlockOrigin origin, int streamId,
                                 boolean endStream, OptionalInt promisedStreamId,
                                 java.util.List<HpackHeaderField> fields,
                                 HpackBlockStatus analysisStatus, int omittedFieldCount,
                                 HpackContextCompleteness contextCompleteness) {

    public DecodedHeaderBlock {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(promisedStreamId, "promisedStreamId");
        fields = HpackHeaderFields.copyOf(fields);
        Objects.requireNonNull(analysisStatus, "analysisStatus");
        Objects.requireNonNull(contextCompleteness, "contextCompleteness");
        if (omittedFieldCount < 0) {
            throw new IllegalArgumentException("omittedFieldCount must be non-negative");
        }
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

    /** Returns whether analysis was incomplete or omitted any fields. */
    public boolean recovered() {
        return analysisStatus == HpackBlockStatus.INCOMPLETE || omittedFieldCount != 0
                || contextCompleteness == HpackContextCompleteness.PARTIAL;
    }
}
