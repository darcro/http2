package dev.darcro.http2.hpack;

import java.util.Objects;
import java.util.OptionalInt;

/** Describes how HPACK supplied a decoded field's name and value. */
public record HpackFieldProvenance(HpackValueSource nameSource,
                                   HpackValueSource valueSource,
                                   int tableIndex) {
    public HpackFieldProvenance {
        Objects.requireNonNull(nameSource, "nameSource");
        Objects.requireNonNull(valueSource, "valueSource");
        if (tableIndex != -1 && tableIndex < 1) {
            throw new IllegalArgumentException("tableIndex must be -1 or positive");
        }
    }

    public OptionalInt optionalTableIndex() {
        return tableIndex < 0 ? OptionalInt.empty() : OptionalInt.of(tableIndex);
    }

    static HpackFieldProvenance literal() {
        return new HpackFieldProvenance(HpackValueSource.LITERAL,
                HpackValueSource.LITERAL, -1);
    }

    static HpackFieldProvenance indexed(HpackValueSource source, int index) {
        return new HpackFieldProvenance(source, source, index);
    }

    static HpackFieldProvenance indexedName(HpackValueSource source, int index) {
        return new HpackFieldProvenance(source, HpackValueSource.LITERAL, index);
    }
}
