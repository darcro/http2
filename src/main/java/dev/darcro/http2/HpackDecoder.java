package dev.darcro.http2;

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
    private int dynamicTableSize;
    private int dynamicTableLimit = 4_096;
    private int maximumTableSize = 4_096;
    private int pendingMinimum = -1;
    private boolean failed;

    public HpackDecoder() {
        this(HpackDecoderConfig.defaults());
    }

    public HpackDecoder(HpackDecoderConfig config) {
        this.config = Objects.requireNonNull(config, "config");
    }

    public HpackDecoderConfig config() {
        return config;
    }

    public boolean failed() {
        return failed;
    }

    public int dynamicTableSize() {
        return dynamicTableSize;
    }

    public int maxDynamicTableSize() {
        return maximumTableSize;
    }

    /**
     * Applies a SETTINGS_HEADER_TABLE_SIZE limit after connection code has
     * determined that the setting takes effect.
     */
    public void updateMaxDynamicTableSize(int maximumSize) {
        if (failed) {
            throw new IllegalStateException("HPACK decoder is in a failed state");
        }
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

    public List<HpackHeaderField> decode(byte[] headerBlock)
            throws HpackDecodingException {
        Objects.requireNonNull(headerBlock, "headerBlock");
        return decode(headerBlock, 0, headerBlock.length);
    }

    public List<HpackHeaderField> decode(byte[] headerBlock, int offset, int length)
            throws HpackDecodingException {
        Objects.requireNonNull(headerBlock, "headerBlock");
        Objects.checkFromIndexSize(offset, length, headerBlock.length);
        return decodeFragments(List.of(new ByteSequence(headerBlock, offset, length)), length);
    }

    public List<HpackHeaderField> decode(ByteSequence headerBlock)
            throws HpackDecodingException {
        Objects.requireNonNull(headerBlock, "headerBlock");
        return decodeFragments(List.of(headerBlock), headerBlock.length());
    }

    List<HpackHeaderField> decodeFragments(List<ByteSequence> fragments, long totalLength)
            throws HpackDecodingException {
        if (failed) {
            throw new HpackDecodingException(HpackErrorReason.DECODER_FAILED, 0,
                    "HPACK decoder is in a failed state");
        }
        try {
            if (totalLength > config.maxEncodedHeaderBlockSize()) {
                throw error(HpackErrorReason.ENCODED_BLOCK_TOO_LARGE, 0,
                        "Encoded header block exceeds configured limit");
            }
            return decodeBlock(new Input(fragments, (int) totalLength));
        } catch (HpackDecodingException exception) {
            failed = true;
            throw exception;
        }
    }

    private List<HpackHeaderField> decodeBlock(Input input) throws HpackDecodingException {
        List<HpackHeaderField> fields = new ArrayList<>();
        long headerListSize = 0;
        boolean fieldSeen = false;
        int tableUpdates = 0;
        boolean updateRequired = pendingMinimum >= 0;

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
                continue;
            }

            fieldSeen = true;
            HpackHeaderField field;
            if ((first & 0x80) != 0) {
                int index = decodeInteger(first, 7, input, representationOffset);
                HpackTables.Entry entry = get(index, representationOffset);
                field = field(entry.name(), entry.value(), false);
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
                byte[] name = nameIndex == 0
                        ? decodeString(input, (int) available)
                        : get(nameIndex, representationOffset).name();
                if (name.length > available) {
                    throw error(HpackErrorReason.HEADER_LIST_TOO_LARGE,
                            representationOffset,
                            "Header name exceeds configured header-list limit");
                }
                byte[] value = decodeString(input, (int) available - name.length);
                field = field(name, value, sensitive);
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
        return List.copyOf(fields);
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

    private HpackTables.Entry get(int index, int offset) throws HpackDecodingException {
        if (index <= 0) {
            throw error(HpackErrorReason.INVALID_INDEX, offset,
                    "HPACK table index zero is invalid");
        }
        if (index <= STATIC_TABLE_LENGTH) {
            return HpackTables.STATIC_TABLE[index - 1];
        }
        int dynamicIndex = index - STATIC_TABLE_LENGTH - 1;
        if (dynamicIndex < 0 || dynamicIndex >= dynamicTable.size()) {
            throw error(HpackErrorReason.INVALID_INDEX, offset,
                    "HPACK table index " + index + " is unavailable");
        }
        return dynamicTable.get(dynamicIndex);
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

    private static HpackHeaderField field(byte[] name, byte[] value, boolean sensitive) {
        return new HpackHeaderField(new ByteSequence(name, 0, name.length),
                new ByteSequence(value, 0, value.length), sensitive);
    }

    private static HpackDecodingException error(HpackErrorReason reason, int offset,
                                                String message) {
        return new HpackDecodingException(reason, offset, message);
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
