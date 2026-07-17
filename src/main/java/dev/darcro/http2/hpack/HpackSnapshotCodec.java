package dev.darcro.http2.hpack;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Versioned binary snapshot codec with backward-compatible version 1 reads. */
final class HpackSnapshotCodec {
    private static final byte[] MAGIC = {'H', '2', 'H', 'P'};
    private static final int HEADER_SIZE = 12;
    private static final int KIND_DECODER = 1;
    private static final int KIND_ASSEMBLER = 2;

    private HpackSnapshotCodec() {
    }

    static byte[] encode(HpackDecoderSnapshot snapshot) {
        int payloadSize = decoderPayloadSize(snapshot);
        Writer writer = new Writer(HEADER_SIZE + payloadSize);
        writeHeader(writer, KIND_DECODER, payloadSize);
        writeDecoderPayload(writer, snapshot);
        return writer.bytes();
    }

    static byte[] encode(HpackFrameAssemblerSnapshot snapshot) {
        int decoderSize = decoderPayloadSize(snapshot.decoderSnapshot());
        long payloadSize = (long) decoderSize + 16 + snapshot.incompleteBlock().length();
        int checkedPayloadSize = checkedSize(payloadSize);
        Writer writer = new Writer(HEADER_SIZE + checkedPayloadSize);
        writeHeader(writer, KIND_ASSEMBLER, checkedPayloadSize);
        writeDecoderPayload(writer, snapshot.decoderSnapshot());
        writer.writeByte(snapshot.active() ? 1 : 0);
        writer.writeByte(originCode(snapshot.origin().orElse(null)));
        writer.writeByte(snapshot.endStream() ? 1 : 0);
        writer.writeByte(snapshot.discarding() ? 1 : 0);
        writer.writeInt(snapshot.streamId());
        writer.writeInt(snapshot.promisedStreamId().orElse(0));
        writer.writeInt(snapshot.incompleteBlock().length());
        writer.writeBytes(snapshot.incompleteBlockBytes());
        return writer.bytes();
    }

    static HpackDecoderSnapshot decodeDecoder(byte[] encoded)
            throws HpackSnapshotException {
        Opened opened = open(encoded, KIND_DECODER);
        Reader reader = opened.reader();
        HpackDecoderSnapshot result = readDecoderPayload(reader, opened.version());
        reader.requireEnd();
        return result;
    }

    static HpackFrameAssemblerSnapshot decodeAssembler(byte[] encoded)
            throws HpackSnapshotException {
        Opened opened = open(encoded, KIND_ASSEMBLER);
        Reader reader = opened.reader();
        HpackDecoderSnapshot decoder = readDecoderPayload(reader, opened.version());
        int activeValue = reader.readByte();
        int originCode = reader.readByte();
        int endStreamValue = reader.readByte();
        int discardValue = reader.readByte();
        int streamId = reader.readInt();
        int promisedStreamId = reader.readInt();
        int fragmentLength = reader.readLength("incomplete block");
        byte[] incomplete = reader.readBytes(fragmentLength);
        reader.requireEnd();

        if (activeValue > 1 || endStreamValue > 1 || discardValue > 1
                || (opened.version() == 1 && discardValue != 0)) {
            throw error(HpackSnapshotErrorReason.INVALID_ASSEMBLER_STATE,
                    reader.position(), "Invalid assembler flags or reserved byte");
        }
        boolean active = activeValue == 1;
        boolean discarding = discardValue == 1;
        boolean endStream = endStreamValue == 1;
        HeaderBlockOrigin origin = decodeOrigin(originCode, reader.position());
        validateAssembler(active, discarding, origin, streamId, endStream, promisedStreamId,
                incomplete.length, reader.position());
        return new HpackFrameAssemblerSnapshot(decoder, active, discarding, origin, streamId,
                endStream, promisedStreamId, incomplete);
    }

    private static Opened open(byte[] encoded, int expectedKind)
            throws HpackSnapshotException {
        Objects.requireNonNull(encoded, "encoded");
        Reader reader = new Reader(encoded);
        for (byte expected : MAGIC) {
            if (reader.readByte() != Byte.toUnsignedInt(expected)) {
                throw error(HpackSnapshotErrorReason.INVALID_MAGIC,
                        reader.position() - 1, "Invalid HPACK snapshot magic");
            }
        }
        int version = reader.readByte();
        if (version != 1 && version != HpackDecoderSnapshot.FORMAT_VERSION) {
            throw error(HpackSnapshotErrorReason.UNSUPPORTED_VERSION,
                    reader.position() - 1, "Unsupported HPACK snapshot version " + version);
        }
        int kind = reader.readByte();
        if (kind != expectedKind) {
            throw error(HpackSnapshotErrorReason.INVALID_KIND,
                    reader.position() - 1, "Unexpected HPACK snapshot kind " + kind);
        }
        if (reader.readByte() != 0 || reader.readByte() != 0) {
            throw error(HpackSnapshotErrorReason.INVALID_DECODER_STATE,
                    reader.position() - 2, "Snapshot reserved bytes must be zero");
        }
        int payloadLength = reader.readLength("payload");
        if (payloadLength < reader.remaining()) {
            throw error(HpackSnapshotErrorReason.TRAILING_DATA, reader.position() + payloadLength,
                    "Snapshot contains trailing bytes");
        }
        if (payloadLength > reader.remaining()) {
            throw error(HpackSnapshotErrorReason.TRUNCATED_INPUT, reader.position(),
                    "Snapshot payload is truncated");
        }
        return new Opened(reader, version);
    }

    private static void writeHeader(Writer writer, int kind, int payloadSize) {
        writer.writeBytes(MAGIC);
        writer.writeByte(HpackDecoderSnapshot.FORMAT_VERSION);
        writer.writeByte(kind);
        writer.writeByte(0);
        writer.writeByte(0);
        writer.writeInt(payloadSize);
    }

    private static int decoderPayloadSize(HpackDecoderSnapshot snapshot) {
        long size = 20;
        for (HpackDynamicTableEntry entry : snapshot.dynamicTableEntries()) {
            size += 8L + entry.name().length() + entry.value().length();
        }
        return checkedSize(size);
    }

    private static void writeDecoderPayload(Writer writer, HpackDecoderSnapshot snapshot) {
        writer.writeInt(snapshot.dynamicTableLimit());
        writer.writeInt(snapshot.maximumTableSize());
        writer.writeInt(snapshot.pendingMinimum().orElse(-1));
        writer.writeInt(snapshot.dynamicTableEntries().size());
        for (HpackDynamicTableEntry entry : snapshot.dynamicTableEntries()) {
            writer.writeInt(entry.name().length());
            writer.writeInt(entry.value().length());
            writer.writeBytes(entry.nameBytes());
            writer.writeBytes(entry.valueBytes());
        }
        writer.writeByte(snapshot.contextCompleteness()
                == HpackContextCompleteness.OBSERVED_COMPLETE ? 1 : 0);
        writer.writeByte(snapshot.tableLimitKnown() ? 1 : 0);
        writer.writeByte(0);
        writer.writeByte(0);
    }

    private static HpackDecoderSnapshot readDecoderPayload(Reader reader, int version)
            throws HpackSnapshotException {
        int dynamicTableLimit = reader.readInt();
        int maximumTableSize = reader.readInt();
        int pendingMinimum = reader.readInt();
        int entryCount = reader.readLength("dynamic table entry count");
        if (entryCount > reader.remaining() / 8) {
            throw error(HpackSnapshotErrorReason.INVALID_LENGTH, reader.position() - 4,
                    "Dynamic table entry count exceeds available bytes");
        }

        List<HpackDynamicTableEntry> entries = new ArrayList<>(entryCount);
        long tableSize = 0;
        for (int i = 0; i < entryCount; i++) {
            int nameLength = reader.readLength("entry name");
            int valueLength = reader.readLength("entry value");
            if ((long) nameLength + valueLength > reader.remaining()) {
                throw error(HpackSnapshotErrorReason.TRUNCATED_INPUT, reader.position(),
                        "Dynamic table entry is truncated");
            }
            byte[] name = reader.readBytes(nameLength);
            byte[] value = reader.readBytes(valueLength);
            HpackDynamicTableEntry entry = new HpackDynamicTableEntry(name, value);
            tableSize += (long) nameLength + valueLength + 32;
            if (tableSize > Integer.MAX_VALUE) {
                throw error(HpackSnapshotErrorReason.INVALID_LENGTH, reader.position(),
                        "Dynamic table size overflows implementation limits");
            }
            entries.add(entry);
        }

        HpackContextCompleteness contextCompleteness = HpackContextCompleteness.PARTIAL;
        boolean tableLimitKnown = false;
        if (version >= 2) {
            int completenessCode = reader.readByte();
            int knownCode = reader.readByte();
            int reserved1 = reader.readByte();
            int reserved2 = reader.readByte();
            if (completenessCode > 1 || knownCode > 1 || reserved1 != 0
                    || reserved2 != 0) {
                throw error(HpackSnapshotErrorReason.INVALID_DECODER_STATE,
                        reader.position() - 4, "Invalid decoder confidence state");
            }
            contextCompleteness = completenessCode == 1
                    ? HpackContextCompleteness.OBSERVED_COMPLETE
                    : HpackContextCompleteness.PARTIAL;
            tableLimitKnown = knownCode == 1;
            if (contextCompleteness == HpackContextCompleteness.OBSERVED_COMPLETE
                    && !tableLimitKnown) {
                throw error(HpackSnapshotErrorReason.INVALID_DECODER_STATE,
                        reader.position() - 4,
                        "Observed-complete context requires a known table limit");
            }
        }

        validateDecoder(dynamicTableLimit, maximumTableSize, pendingMinimum,
                tableSize, reader.position());
        return new HpackDecoderSnapshot(dynamicTableLimit, maximumTableSize,
                pendingMinimum, contextCompleteness, tableLimitKnown, entries);
    }

    private static void validateDecoder(int dynamicLimit, int maximum,
                                        int pending, long tableSize, int offset)
            throws HpackSnapshotException {
        if (dynamicLimit < 0 || maximum < 0 || pending < -1) {
            throw error(HpackSnapshotErrorReason.INVALID_DECODER_STATE, offset,
                    "Decoder table limits must be non-negative");
        }
        if (pending >= 0 && pending > maximum) {
            throw error(HpackSnapshotErrorReason.INVALID_DECODER_STATE, offset,
                    "Pending minimum exceeds the applied protocol maximum");
        }
        if (pending < 0 && dynamicLimit > maximum) {
            throw error(HpackSnapshotErrorReason.INVALID_DECODER_STATE, offset,
                    "Dynamic table limit exceeds the applied protocol maximum");
        }
        if (tableSize > dynamicLimit) {
            throw error(HpackSnapshotErrorReason.INVALID_DECODER_STATE, offset,
                    "Dynamic entries exceed the encoded table limit");
        }
    }

    private static void validateAssembler(boolean active, boolean discarding,
                                          HeaderBlockOrigin origin,
                                          int streamId, boolean endStream,
                                          int promisedStreamId, int fragmentLength,
                                          int offset) throws HpackSnapshotException {
        if (discarding) {
            if (active || origin != null || streamId <= 0 || endStream
                    || promisedStreamId != 0 || fragmentLength != 0) {
                throw error(HpackSnapshotErrorReason.INVALID_ASSEMBLER_STATE, offset,
                        "Invalid discarding assembler state");
            }
            return;
        }
        if (!active) {
            if (origin != null || streamId != 0 || endStream || promisedStreamId != 0
                    || fragmentLength != 0) {
                throw error(HpackSnapshotErrorReason.INVALID_ASSEMBLER_STATE, offset,
                        "Inactive assembler contains field-block state");
            }
            return;
        }
        if (origin == null || streamId <= 0) {
            throw error(HpackSnapshotErrorReason.INVALID_ASSEMBLER_STATE, offset,
                    "Active assembler requires an origin and stream identifier");
        }
        if (origin == HeaderBlockOrigin.HEADERS && promisedStreamId != 0) {
            throw error(HpackSnapshotErrorReason.INVALID_ASSEMBLER_STATE, offset,
                    "HEADERS state cannot contain a promised stream identifier");
        }
        if (origin == HeaderBlockOrigin.PUSH_PROMISE
                && (promisedStreamId <= 0 || endStream)) {
            throw error(HpackSnapshotErrorReason.INVALID_ASSEMBLER_STATE, offset,
                    "Invalid PUSH_PROMISE assembler metadata");
        }
    }

    private static int originCode(HeaderBlockOrigin origin) {
        if (origin == null) {
            return 0;
        }
        return origin == HeaderBlockOrigin.HEADERS ? 1 : 2;
    }

    private static HeaderBlockOrigin decodeOrigin(int code, int offset)
            throws HpackSnapshotException {
        return switch (code) {
            case 0 -> null;
            case 1 -> HeaderBlockOrigin.HEADERS;
            case 2 -> HeaderBlockOrigin.PUSH_PROMISE;
            default -> throw error(HpackSnapshotErrorReason.INVALID_ASSEMBLER_STATE,
                    offset, "Invalid field-block origin " + code);
        };
    }

    private static int checkedSize(long size) {
        if (size < 0 || size > Integer.MAX_VALUE - HEADER_SIZE) {
            throw new IllegalStateException("HPACK snapshot is too large to encode");
        }
        return (int) size;
    }

    private static HpackSnapshotException error(HpackSnapshotErrorReason reason,
                                                int offset, String message) {
        return new HpackSnapshotException(reason, offset, message);
    }

    private record Opened(Reader reader, int version) {
    }

    private static final class Writer {
        private final byte[] bytes;
        private int position;

        private Writer(int size) {
            bytes = new byte[size];
        }

        void writeByte(int value) {
            bytes[position++] = (byte) value;
        }

        void writeInt(int value) {
            writeByte(value >>> 24);
            writeByte(value >>> 16);
            writeByte(value >>> 8);
            writeByte(value);
        }

        void writeBytes(byte[] value) {
            System.arraycopy(value, 0, bytes, position, value.length);
            position += value.length;
        }

        byte[] bytes() {
            return bytes;
        }
    }

    private static final class Reader {
        private final byte[] bytes;
        private int position;

        private Reader(byte[] bytes) {
            this.bytes = bytes;
        }

        int position() {
            return position;
        }

        int remaining() {
            return bytes.length - position;
        }

        int readByte() throws HpackSnapshotException {
            if (position >= bytes.length) {
                throw error(HpackSnapshotErrorReason.TRUNCATED_INPUT, position,
                        "Unexpected end of HPACK snapshot");
            }
            return Byte.toUnsignedInt(bytes[position++]);
        }

        int readInt() throws HpackSnapshotException {
            if (remaining() < 4) {
                throw error(HpackSnapshotErrorReason.TRUNCATED_INPUT, position,
                        "Unexpected end of HPACK snapshot integer");
            }
            return (readByte() << 24) | (readByte() << 16)
                    | (readByte() << 8) | readByte();
        }

        int readLength(String description) throws HpackSnapshotException {
            int offset = position;
            int value = readInt();
            if (value < 0) {
                throw error(HpackSnapshotErrorReason.INVALID_LENGTH, offset,
                        "Negative " + description + " length");
            }
            return value;
        }

        byte[] readBytes(int length) throws HpackSnapshotException {
            if (length < 0 || length > remaining()) {
                throw error(HpackSnapshotErrorReason.TRUNCATED_INPUT, position,
                        "Snapshot byte sequence is truncated");
            }
            byte[] result = new byte[length];
            System.arraycopy(bytes, position, result, 0, length);
            position += length;
            return result;
        }

        void requireEnd() throws HpackSnapshotException {
            if (position != bytes.length) {
                throw error(HpackSnapshotErrorReason.TRAILING_DATA, position,
                        "Snapshot contains trailing bytes");
            }
        }
    }
}
