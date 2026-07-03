package dev.darcro.http2.hpack;

import dev.darcro.http2.frame.ByteSequence;
import java.util.AbstractList;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.RandomAccess;

/**
 * An immutable, ordered header-field list with allocation-free name matching.
 * Header names are matched case-insensitively using ASCII rules.
 */
public final class HpackHeaderFields extends AbstractList<HpackHeaderField>
        implements RandomAccess {
    private static final String METHOD = ":method";
    private static final String SCHEME = ":scheme";
    private static final String AUTHORITY = ":authority";
    private static final String PATH = ":path";
    private static final String STATUS = ":status";

    private final List<HpackHeaderField> fields;
    private final Optional<String> method;
    private final Optional<String> scheme;
    private final Optional<String> authority;
    private final Optional<String> path;
    private final Optional<String> status;
    private final boolean duplicatePseudoHeaders;

    private HpackHeaderFields(List<HpackHeaderField> source) {
        fields = List.copyOf(source);

        String methodValue = null;
        String schemeValue = null;
        String authorityValue = null;
        String pathValue = null;
        String statusValue = null;
        boolean duplicates = false;

        for (HpackHeaderField field : fields) {
            ByteSequence name = field.name();
            if (asciiEqualsIgnoreCase(name, METHOD)) {
                if (methodValue == null) {
                    methodValue = field.valueUtf8();
                } else {
                    duplicates = true;
                }
            } else if (asciiEqualsIgnoreCase(name, SCHEME)) {
                if (schemeValue == null) {
                    schemeValue = field.valueUtf8();
                } else {
                    duplicates = true;
                }
            } else if (asciiEqualsIgnoreCase(name, AUTHORITY)) {
                if (authorityValue == null) {
                    authorityValue = field.valueUtf8();
                } else {
                    duplicates = true;
                }
            } else if (asciiEqualsIgnoreCase(name, PATH)) {
                if (pathValue == null) {
                    pathValue = field.valueUtf8();
                } else {
                    duplicates = true;
                }
            } else if (asciiEqualsIgnoreCase(name, STATUS)) {
                if (statusValue == null) {
                    statusValue = field.valueUtf8();
                } else {
                    duplicates = true;
                }
            }
        }

        method = Optional.ofNullable(methodValue);
        scheme = Optional.ofNullable(schemeValue);
        authority = Optional.ofNullable(authorityValue);
        path = Optional.ofNullable(pathValue);
        status = Optional.ofNullable(statusValue);
        duplicatePseudoHeaders = duplicates;
    }

    /** Returns an immutable view, defensively copying a plain input list. */
    public static HpackHeaderFields copyOf(List<HpackHeaderField> fields) {
        Objects.requireNonNull(fields, "fields");
        return fields instanceof HpackHeaderFields existing
                ? existing : new HpackHeaderFields(fields);
    }

    /** Returns the first matching field in wire order. */
    public Optional<HpackHeaderField> first(CharSequence name) {
        Objects.requireNonNull(name, "name");
        for (HpackHeaderField field : fields) {
            if (asciiEqualsIgnoreCase(field.name(), name)) {
                return Optional.of(field);
            }
        }
        return Optional.empty();
    }

    /** Returns all matching fields in wire order. */
    public List<HpackHeaderField> all(CharSequence name) {
        Objects.requireNonNull(name, "name");
        List<HpackHeaderField> matches = null;
        for (HpackHeaderField field : fields) {
            if (asciiEqualsIgnoreCase(field.name(), name)) {
                if (matches == null) {
                    matches = new ArrayList<>();
                }
                matches.add(field);
            }
        }
        return matches == null ? List.of() : List.copyOf(matches);
    }

    /** Returns whether at least one field has the supplied name. */
    public boolean contains(CharSequence name) {
        Objects.requireNonNull(name, "name");
        for (HpackHeaderField field : fields) {
            if (asciiEqualsIgnoreCase(field.name(), name)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public HpackHeaderField get(int index) {
        return fields.get(index);
    }

    @Override
    public int size() {
        return fields.size();
    }

    Optional<String> method() {
        return method;
    }

    Optional<String> scheme() {
        return scheme;
    }

    Optional<String> authority() {
        return authority;
    }

    Optional<String> path() {
        return path;
    }

    Optional<String> status() {
        return status;
    }

    boolean hasDuplicatePseudoHeaders() {
        return duplicatePseudoHeaders;
    }

    private static boolean asciiEqualsIgnoreCase(ByteSequence bytes, CharSequence text) {
        if (bytes.length() != text.length()) {
            return false;
        }
        for (int i = 0; i < bytes.length(); i++) {
            int left = bytes.unsignedByteAt(i);
            char right = text.charAt(i);
            if (left > 0x7f || right > 0x7f
                    || toLowerAscii(left) != toLowerAscii(right)) {
                return false;
            }
        }
        return true;
    }

    private static int toLowerAscii(int value) {
        return value >= 'A' && value <= 'Z' ? value + ('a' - 'A') : value;
    }
}
