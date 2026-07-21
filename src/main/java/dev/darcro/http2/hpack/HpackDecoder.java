package dev.darcro.http2.hpack;

import dev.darcro.http2.frame.ByteSequence;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Stateful RFC 7541 decoder. One instance is required for each inbound
 * connection direction. This class is not thread-safe.
 */
public final class HpackDecoder {
    private static final int STATIC_TABLE_LENGTH = HpackTables.STATIC_TABLE.length;

    private final HpackDecoderConfig config;
    private final List<HpackTables.Entry> dynamicTable = new ArrayList<>();
    private final List<HpackDiagnostic> diagnostics = new ArrayList<>();
    private int dynamicTableSize;
    private int dynamicTableLimit = 4_096;
    private int maximumTableSize = 4_096;
    private int pendingMinimum = -1;
    private boolean tableLimitKnown;
    private HpackContextCompleteness contextCompleteness;
    private int diagnosticStreamId = -1;

    public HpackDecoder() {
        this(HpackDecoderConfig.defaults(), HpackContextCompleteness.PARTIAL, false);
    }

    public HpackDecoder(HpackDecoderConfig config) {
        this(config, HpackContextCompleteness.PARTIAL, false);
    }

    private HpackDecoder(HpackDecoderConfig config,
                         HpackContextCompleteness contextCompleteness,
                         boolean tableLimitKnown) {
        this.config = Objects.requireNonNull(config, "config");
        this.contextCompleteness = Objects.requireNonNull(contextCompleteness,
                "contextCompleteness");
        this.tableLimitKnown = tableLimitKnown;
        maximumTableSize = config.maxDynamicTableCapacity();
    }

    public static HpackDecoder atConnectionStart() {
        return atConnectionStart(HpackDecoderConfig.defaults());
    }

    public static HpackDecoder atConnectionStart(HpackDecoderConfig config) {
        HpackDecoder decoder = new HpackDecoder(config,
                HpackContextCompleteness.OBSERVED_COMPLETE, true);
        decoder.maximumTableSize = 4_096;
        return decoder;
    }

    /** Restores a healthy decoder under caller-supplied local resource limits. */
    public static HpackDecoder restore(HpackDecoderSnapshot snapshot,
                                       HpackDecoderConfig config)
            throws HpackSnapshotException {
        Objects.requireNonNull(snapshot, "snapshot");
        Objects.requireNonNull(config, "config");
        validateSnapshotConfiguration(snapshot, config);

        HpackDecoder decoder = new HpackDecoder(config,
                snapshot.contextCompleteness(), snapshot.tableLimitKnown());
        decoder.dynamicTableLimit = snapshot.dynamicTableLimit();
        decoder.maximumTableSize = snapshot.maximumTableSize();
        decoder.pendingMinimum = snapshot.pendingMinimum().orElse(-1);
        for (HpackDynamicTableEntry entry : snapshot.dynamicTableEntries()) {
            HpackTables.Entry restored = new HpackTables.Entry(
                    entry.nameBytes(), entry.valueBytes());
            decoder.dynamicTable.add(restored);
            decoder.dynamicTableSize += restored.size();
        }
        return decoder;
    }

    public HpackDecoderConfig config() {
        return config;
    }

    public HpackContextCompleteness contextCompleteness() {
        return contextCompleteness;
    }

    public boolean tableLimitKnown() {
        return tableLimitKnown;
    }

    public int dynamicTableSize() {
        return dynamicTableSize;
    }

    public int maxDynamicTableSize() {
        return maximumTableSize;
    }

    /** Captures immutable state at the boundary between analysis calls. */
    public HpackDecoderSnapshot snapshot() {
        List<HpackDynamicTableEntry> entries = new ArrayList<>(dynamicTable.size());
        for (HpackTables.Entry entry : dynamicTable) {
            entries.add(new HpackDynamicTableEntry(entry.name(), entry.value()));
        }
        return new HpackDecoderSnapshot(dynamicTableLimit, maximumTableSize,
                pendingMinimum, contextCompleteness, tableLimitKnown, entries);
    }

    /**
     * Applies a SETTINGS_HEADER_TABLE_SIZE limit after connection code has
     * determined that the setting takes effect.
     */
    public void updateMaxDynamicTableSize(int maximumSize) {
        if (maximumSize < 0 || maximumSize > config.maxDynamicTableCapacity()) {
            throw new IllegalArgumentException("maximumSize must be between 0 and "
                    + config.maxDynamicTableCapacity());
        }
        if (maximumSize < maximumTableSize) {
            pendingMinimum = pendingMinimum < 0
                    ? maximumSize : Math.min(pendingMinimum, maximumSize);
        }
        maximumTableSize = maximumSize;
    }

    public HpackBlockAnalysis analyze(byte[] headerBlock) {
        Objects.requireNonNull(headerBlock, "headerBlock");
        return analyze(headerBlock, 0, headerBlock.length);
    }

    public HpackBlockAnalysis analyze(byte[] headerBlock, int offset, int length) {
        Objects.requireNonNull(headerBlock, "headerBlock");
        Objects.checkFromIndexSize(offset, length, headerBlock.length);
        return analyzeFragments(List.of(ByteSequence.wrap(headerBlock, offset, length)),
                length);
    }

    public HpackBlockAnalysis analyze(ByteSequence headerBlock) {
        Objects.requireNonNull(headerBlock, "headerBlock");
        return analyzeFragments(List.of(headerBlock), headerBlock.length());
    }

    HpackBlockAnalysis analyzeFragments(List<ByteSequence> fragments, long totalLength) {
        diagnostics.clear();
        HpackBlockAnalysis analysis;
        if (totalLength > config.maxEncodedHeaderBlockSize()) {
            String message = "Encoded header block exceeds configured limit";
            emit(new HpackDiagnostic(HpackDiagnosticReason.RESOURCE_LIMIT, 0, -1,
                    -1, message));
            loseContext(true, message);
            analysis = incomplete(List.of(), 0);
        } else {
            analysis = decodeBlock(new Input(fragments, (int) totalLength));
        }
        return withDiagnostics(analysis);
    }

    HpackBlockAnalysis analyzeFragments(List<ByteSequence> fragments, long totalLength,
                                        int streamId) {
        diagnosticStreamId = streamId;
        try {
            return analyzeFragments(fragments, totalLength);
        } finally {
            diagnosticStreamId = -1;
        }
    }

    List<HpackDiagnostic> discardObservedContext(String message) {
        diagnostics.clear();
        loseContext(true, message);
        return List.copyOf(diagnostics);
    }

    private HpackBlockAnalysis decodeBlock(Input input) {
        List<HpackHeaderField> fields = new ArrayList<>();
        int omittedFields = 0;
        long headerListSize = 0;
        boolean fieldSeen = false;
        int tableUpdates = 0;
        boolean updateRequired = pendingMinimum >= 0;

        try {
        while (input.hasRemaining()) {
            int representationOffset = input.position();
            int first = input.read();
            boolean tableUpdate = (first & 0xe0) == 0x20;

            if (updateRequired && !tableUpdate) {
                throw error(HpackErrorReason.INVALID_TABLE_SIZE_UPDATE,
                        representationOffset,
                        "Required dynamic table size update is missing");
            }

            if (tableUpdate) {
                if (fieldSeen || ++tableUpdates > 2) {
                    throw error(HpackErrorReason.INVALID_TABLE_SIZE_UPDATE,
                            representationOffset,
                            "Dynamic table size updates must appear first and at most twice");
                }
                int newSize = decodeInteger(first, 5, input, representationOffset);
                if (newSize > maximumTableSize) {
                    throw error(HpackErrorReason.INVALID_TABLE_SIZE_UPDATE,
                            representationOffset,
                            "Dynamic table size update exceeds the configured maximum");
                }
                if (updateRequired) {
                    if (newSize > pendingMinimum) {
                        throw error(HpackErrorReason.INVALID_TABLE_SIZE_UPDATE,
                                representationOffset,
                                "Required table update exceeds the smallest pending limit");
                    }
                    updateRequired = false;
                }
                setDynamicTableLimit(newSize);
                tableLimitKnown = true;
                if (newSize == 0) {
                    contextCompleteness = HpackContextCompleteness.OBSERVED_COMPLETE;
                }
                continue;
            }

            fieldSeen = true;
            HpackHeaderField field;
            if ((first & 0x80) != 0) {
                int index = decodeInteger(first, 7, input, representationOffset);
                Lookup lookup = get(index, representationOffset,
                        HpackDiagnosticReason.MISSING_DYNAMIC_TABLE_INDEX);
                if (lookup == null) {
                    omittedFields++;
                    continue;
                }
                field = field(lookup.entry().name(), lookup.entry().value(), false,
                        HpackFieldProvenance.indexed(lookup.source(), index));
            } else {
                boolean incremental = (first & 0x40) != 0;
                boolean sensitive = !incremental && (first & 0x10) != 0;
                int prefix = incremental ? 6 : 4;
                int nameIndex = decodeInteger(first, prefix, input, representationOffset);
                long available = config.maxDecodedHeaderListSize() - headerListSize - 32L;
                if (available < 0) {
                    throw error(HpackErrorReason.HEADER_LIST_TOO_LARGE,
                            representationOffset,
                            "Decoded header list exceeds configured limit");
                }
                Lookup nameLookup = null;
                boolean missingName = false;
                byte[] name;
                if (nameIndex == 0) {
                    name = decodeString(input, (int) available);
                } else {
                    nameLookup = get(nameIndex, representationOffset,
                            HpackDiagnosticReason.MISSING_DYNAMIC_TABLE_NAME_INDEX);
                    missingName = nameLookup == null;
                    name = missingName ? null : nameLookup.entry().name();
                }
                if (!missingName && name.length > available) {
                    throw error(HpackErrorReason.HEADER_LIST_TOO_LARGE,
                            representationOffset,
                            "Header name exceeds configured header-list limit");
                }
                int maxValue = missingName
                        ? (int) available : (int) available - name.length;
                byte[] value = decodeString(input, maxValue);
                if (missingName) {
                    // The name length is unknowable, but the known value and
                    // per-field overhead must still consume the resource
                    // budget for best-effort output.
                    headerListSize += value.length + 32L;
                    omittedFields++;
                    if (incremental) {
                        loseContext(false,
                                "Unknown incrementally indexed field changed table positions");
                    }
                    continue;
                }
                HpackFieldProvenance provenance = nameIndex == 0
                        ? HpackFieldProvenance.literal()
                        : HpackFieldProvenance.indexedName(nameLookup.source(), nameIndex);
                field = field(name, value, sensitive, provenance);
                if (incremental) {
                    add(name, value);
                }
            }

            headerListSize += field.name().length() + field.value().length() + 32L;
            if (headerListSize > config.maxDecodedHeaderListSize()) {
                throw error(HpackErrorReason.HEADER_LIST_TOO_LARGE,
                        representationOffset,
                        "Decoded header list exceeds configured limit");
            }
            fields.add(field);
        }

        if (updateRequired) {
            throw error(HpackErrorReason.INVALID_TABLE_SIZE_UPDATE, input.position(),
                    "Required dynamic table size update is missing");
        }
        pendingMinimum = -1;
        return new HpackBlockAnalysis(HpackHeaderFields.copyOf(fields),
                HpackBlockStatus.COMPLETE, omittedFields, contextCompleteness, List.of());
        } catch (HpackDecodingException exception) {
            HpackDiagnosticReason reason = isResourceError(exception.reason())
                    ? HpackDiagnosticReason.RESOURCE_LIMIT
                    : HpackDiagnosticReason.MALFORMED_BLOCK;
            emit(new HpackDiagnostic(reason, exception.offset(), -1, -1,
                    exception.getMessage()));
            loseContext(true, exception.getMessage());
            return incomplete(fields, omittedFields);
        }
    }

    private int decodeInteger(int first, int prefixBits, Input input, int offset)
            throws HpackDecodingException {
        int prefixMaximum = (1 << prefixBits) - 1;
        int prefix = first & prefixMaximum;
        if (prefix < prefixMaximum) {
            return prefix;
        }

        long result = prefixMaximum;
        int shift = 0;
        int octets = 0;
        while (true) {
            int next = input.read();
            if (++octets > 5 || shift >= 35) {
                throw error(HpackErrorReason.INTEGER_OVERFLOW, offset,
                        "HPACK integer representation is too long");
            }
            result += (long) (next & 0x7f) << shift;
            if (result > Integer.MAX_VALUE) {
                throw error(HpackErrorReason.INTEGER_OVERFLOW, offset,
                        "HPACK integer exceeds implementation limit");
            }
            if ((next & 0x80) == 0) {
                return (int) result;
            }
            shift += 7;
        }
    }

    private byte[] decodeString(Input input, int maxOutput) throws HpackDecodingException {
        int offset = input.position();
        int first = input.read();
        boolean huffman = (first & 0x80) != 0;
        int length = decodeInteger(first, 7, input, offset);
        if (length > input.remaining()) {
            throw error(HpackErrorReason.TRUNCATED_INPUT, input.position(),
                    "HPACK string is shorter than its declared length");
        }
        if (!huffman && length > maxOutput) {
            throw error(HpackErrorReason.HEADER_LIST_TOO_LARGE, offset,
                    "HPACK string exceeds configured header-list limit");
        }
        byte[] encoded = input.readBytes(length);
        return huffman
                ? HpackHuffman.decode(encoded, maxOutput, offset)
                : encoded;
    }

    private Lookup get(int index, int offset, HpackDiagnosticReason reason)
            throws HpackDecodingException {
        if (index <= 0) {
            throw error(HpackErrorReason.INVALID_INDEX, offset,
                    "HPACK table index zero is invalid");
        }
        if (index <= STATIC_TABLE_LENGTH) {
            return new Lookup(HpackTables.STATIC_TABLE[index - 1],
                    HpackValueSource.STATIC_TABLE);
        }
        int dynamicIndex = index - STATIC_TABLE_LENGTH - 1;
        if (dynamicIndex < 0 || dynamicIndex >= dynamicTable.size()) {
            emit(new HpackDiagnostic(reason, offset, index, -1,
                    "HPACK dynamic table index " + index
                            + " is unavailable in the observed context"));
            markPartialPreservingTable(
                    "Unavailable dynamic index indicates incomplete observed context");
            return null;
        }
        return new Lookup(dynamicTable.get(dynamicIndex),
                HpackValueSource.DYNAMIC_TABLE);
    }

    private void add(byte[] name, byte[] value) {
        HpackTables.Entry entry = new HpackTables.Entry(name, value);
        int size = entry.size();
        if (size > dynamicTableLimit) {
            dynamicTable.clear();
            dynamicTableSize = 0;
            return;
        }
        dynamicTable.add(0, entry);
        dynamicTableSize += size;
        evict();
    }

    private void setDynamicTableLimit(int newLimit) {
        dynamicTableLimit = newLimit;
        evict();
    }

    private void evict() {
        while (dynamicTableSize > dynamicTableLimit) {
            HpackTables.Entry removed = dynamicTable.remove(dynamicTable.size() - 1);
            dynamicTableSize -= removed.size();
        }
    }

    private static HpackHeaderField field(byte[] name, byte[] value, boolean sensitive,
                                          HpackFieldProvenance provenance) {
        return new HpackHeaderField(ByteSequence.wrap(name),
                ByteSequence.wrap(value), sensitive, provenance);
    }

    private static HpackDecodingException error(HpackErrorReason reason, int offset,
                                                String message) {
        return new HpackDecodingException(reason, offset, message);
    }

    private HpackBlockAnalysis incomplete(List<HpackHeaderField> fields,
                                          int omittedFields) {
        return new HpackBlockAnalysis(HpackHeaderFields.copyOf(fields),
                HpackBlockStatus.INCOMPLETE, omittedFields, contextCompleteness, List.of());
    }

    private static boolean isResourceError(HpackErrorReason reason) {
        return reason == HpackErrorReason.ENCODED_BLOCK_TOO_LARGE
                || reason == HpackErrorReason.HEADER_LIST_TOO_LARGE;
    }

    private void loseContext(boolean loseTableLimit, String message) {
        boolean transition = contextCompleteness != HpackContextCompleteness.PARTIAL
                || !dynamicTable.isEmpty() || (loseTableLimit && tableLimitKnown);
        dynamicTable.clear();
        dynamicTableSize = 0;
        contextCompleteness = HpackContextCompleteness.PARTIAL;
        if (loseTableLimit) {
            tableLimitKnown = false;
            pendingMinimum = -1;
        }
        if (transition) {
            emit(new HpackDiagnostic(HpackDiagnosticReason.CONTEXT_BECAME_PARTIAL,
                    -1, -1, -1, message));
        }
    }

    private void markPartialPreservingTable(String message) {
        if (contextCompleteness == HpackContextCompleteness.OBSERVED_COMPLETE) {
            contextCompleteness = HpackContextCompleteness.PARTIAL;
            emit(new HpackDiagnostic(HpackDiagnosticReason.CONTEXT_BECAME_PARTIAL,
                    -1, -1, -1, message));
        }
    }

    private void emit(HpackDiagnostic diagnostic) {
        HpackDiagnostic contextual = diagnostic.streamId() < 0 && diagnosticStreamId >= 0
                ? diagnostic.withStreamId(diagnosticStreamId) : diagnostic;
        diagnostics.add(contextual);
    }

    private HpackBlockAnalysis withDiagnostics(HpackBlockAnalysis analysis) {
        return new HpackBlockAnalysis(analysis.fields(), analysis.status(),
                analysis.omittedFieldCount(), analysis.contextCompleteness(), diagnostics);
    }

    private static void validateSnapshotConfiguration(HpackDecoderSnapshot snapshot,
                                                      HpackDecoderConfig config)
            throws HpackSnapshotException {
        if (snapshot.dynamicTableLimit() < 0 || snapshot.maximumTableSize() < 0
                || snapshot.dynamicTableLimit() > config.maxDynamicTableCapacity()
                || snapshot.maximumTableSize() > config.maxDynamicTableCapacity()
                || (snapshot.pendingMinimum().isPresent()
                    && snapshot.pendingMinimum().getAsInt()
                    > config.maxDynamicTableCapacity())) {
            throw snapshotError("Snapshot table limits exceed local configuration");
        }

        long tableSize = 0;
        for (HpackDynamicTableEntry entry : snapshot.dynamicTableEntries()) {
            tableSize += (long) entry.name().length() + entry.value().length() + 32;
            if (tableSize > snapshot.dynamicTableLimit()
                    || tableSize > config.maxDynamicTableCapacity()) {
                throw snapshotError("Snapshot dynamic entries exceed local configuration");
            }
        }
        if (snapshot.pendingMinimum().isEmpty()
                && snapshot.dynamicTableLimit() > snapshot.maximumTableSize()) {
            throw snapshotError("Snapshot dynamic limit exceeds protocol maximum");
        }
        if (snapshot.pendingMinimum().isPresent()
                && snapshot.pendingMinimum().getAsInt() > snapshot.maximumTableSize()) {
            throw snapshotError("Snapshot pending reduction exceeds protocol maximum");
        }
    }

    private static HpackSnapshotException snapshotError(String message) {
        return new HpackSnapshotException(HpackSnapshotErrorReason.CONFIGURATION_LIMIT,
                -1, message);
    }

    private record Lookup(HpackTables.Entry entry, HpackValueSource source) {
    }

    private static final class Input {
        private final List<ByteSequence> fragments;
        private final int totalLength;
        private int fragmentIndex;
        private int fragmentOffset;
        private int position;

        private Input(List<ByteSequence> fragments, int totalLength) {
            this.fragments = List.copyOf(fragments);
            this.totalLength = totalLength;
        }

        boolean hasRemaining() {
            return position < totalLength;
        }

        int remaining() {
            return totalLength - position;
        }

        int position() {
            return position;
        }

        int read() throws HpackDecodingException {
            while (fragmentIndex < fragments.size()
                    && fragmentOffset == fragments.get(fragmentIndex).length()) {
                fragmentIndex++;
                fragmentOffset = 0;
            }
            if (fragmentIndex >= fragments.size() || position >= totalLength) {
                throw error(HpackErrorReason.TRUNCATED_INPUT, position,
                        "Unexpected end of HPACK header block");
            }
            int value = fragments.get(fragmentIndex).unsignedByteAt(fragmentOffset++);
            position++;
            return value;
        }

        byte[] readBytes(int length) throws HpackDecodingException {
            byte[] result = new byte[length];
            for (int i = 0; i < length; i++) {
                result[i] = (byte) read();
            }
            return result;
        }
    }
}
