package dev.darcro.http2.hpack;

import dev.darcro.http2.frame.ByteSequence;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/** An ordered HPACK header field whose name and value are opaque octets. */
public record HpackHeaderField(ByteSequence name, ByteSequence value, boolean sensitive) {
    public HpackHeaderField {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(value, "value");
    }

    public String nameUtf8() {
        return new String(name.toByteArray(), StandardCharsets.UTF_8);
    }

    public String valueUtf8() {
        return new String(value.toByteArray(), StandardCharsets.UTF_8);
    }
}
