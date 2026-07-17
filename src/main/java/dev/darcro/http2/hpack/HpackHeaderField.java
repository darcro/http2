package dev.darcro.http2.hpack;

import dev.darcro.http2.frame.ByteSequence;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** An ordered HPACK header field whose name and value are opaque octets. */
public record HpackHeaderField(ByteSequence name, ByteSequence value, boolean sensitive,
                               HpackFieldProvenance provenance) {
    public HpackHeaderField(ByteSequence name, ByteSequence value, boolean sensitive) {
        this(name, value, sensitive, HpackFieldProvenance.literal());
    }

    public HpackHeaderField {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
        Objects.requireNonNull(provenance, "provenance");
    }

    public String nameUtf8() {
        return name.decode(StandardCharsets.UTF_8);
    }

    public String valueUtf8() {
        return value.decode(StandardCharsets.UTF_8);
    }
}
