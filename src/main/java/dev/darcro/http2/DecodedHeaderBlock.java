package dev.darcro.http2;

import java.util.List;
import java.util.Objects;
import java.util.OptionalInt;

/** A decoded field block plus metadata from its originating HTTP/2 frame. */
public record DecodedHeaderBlock(HeaderBlockOrigin origin, int streamId,
                                 boolean endStream, OptionalInt promisedStreamId,
                                 List<HpackHeaderField> fields) {
    public DecodedHeaderBlock {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(promisedStreamId, "promisedStreamId");
        fields = List.copyOf(fields);
    }
}
