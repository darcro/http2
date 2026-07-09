package dev.darcro.http2.frame;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Stateless parser for exactly one HTTP/2 frame.
 *
 * <p>Parsing is zero-copy. The returned frame can refer to the supplied array,
 * which must not be modified while the frame is in use.</p>
 */
public final class Http2FrameParser {
    public static final int INITIAL_MAX_FRAME_SIZE = 16_384;
    public static final int MIN_MAX_FRAME_SIZE = INITIAL_MAX_FRAME_SIZE;
    public static final int MAX_FRAME_SIZE_LIMIT = 0x00ff_ffff;
    public static final int DEFAULT_MAX_FRAME_SIZE = MAX_FRAME_SIZE_LIMIT;
    private static final int HEADER_LENGTH = 9;

    private final int maxFrameSize;

    public Http2FrameParser() {
        this(DEFAULT_MAX_FRAME_SIZE);
    }

    public Http2FrameParser(int maxFrameSize) {
        if (maxFrameSize < MIN_MAX_FRAME_SIZE || maxFrameSize > MAX_FRAME_SIZE_LIMIT) {
            throw new IllegalArgumentException("maxFrameSize must be between "
                    + MIN_MAX_FRAME_SIZE + " and " + MAX_FRAME_SIZE_LIMIT);
        }
        this.maxFrameSize = maxFrameSize;
    }

    public int maxFrameSize() {
        return maxFrameSize;
    }

    public Http2Frame parse(byte[] frameBytes) throws ParseErrorException {
        Objects.requireNonNull(frameBytes, "frameBytes");
        return parse(frameBytes, 0, frameBytes.length);
    }

    /** Parses exactly one frame from the specified array range. */
    public Http2Frame parse(byte[] frameBytes, int offset, int length)
            throws ParseErrorException {
        Objects.requireNonNull(frameBytes, "frameBytes");
        Objects.checkFromIndexSize(offset, length, frameBytes.length);

        if (length < HEADER_LENGTH) {
            throw error(ParseErrorReason.TRUNCATED_HEADER, length, -1,
                    "HTTP/2 frame header requires 9 bytes; received " + length);
        }

        int payloadLength = unsigned24(frameBytes, offset);
        int type = unsigned8(frameBytes, offset + 3);
        int flags = unsigned8(frameBytes, offset + 4);
        int streamId = signed31(frameBytes, offset + 5);

        if (payloadLength > maxFrameSize) {
            throw error(ParseErrorReason.FRAME_SIZE_ERROR, 0, type,
                    "Frame payload length " + payloadLength
                            + " exceeds configured maximum " + maxFrameSize);
        }

        long expectedLength = HEADER_LENGTH + (long) payloadLength;
        if (length != expectedLength) {
            throw error(ParseErrorReason.LENGTH_MISMATCH,
                    Math.min(length, HEADER_LENGTH + payloadLength), type,
                    "Frame declares " + payloadLength + " payload bytes but range contains "
                            + Math.max(0, length - HEADER_LENGTH));
        }

        int payloadOffset = offset + HEADER_LENGTH;
        return switch (type) {
            case Http2FrameTypes.DATA -> parseData(frameBytes, payloadOffset,
                    payloadLength, flags, streamId);
            case Http2FrameTypes.HEADERS -> parseHeaders(frameBytes, payloadOffset,
                    payloadLength, flags, streamId);
            case Http2FrameTypes.PRIORITY -> parsePriority(frameBytes, payloadOffset,
                    payloadLength, flags, streamId);
            case Http2FrameTypes.RST_STREAM -> parseRstStream(frameBytes, payloadOffset,
                    payloadLength, flags, streamId);
            case Http2FrameTypes.SETTINGS -> parseSettings(frameBytes, payloadOffset,
                    payloadLength, flags, streamId);
            case Http2FrameTypes.PUSH_PROMISE -> parsePushPromise(frameBytes, payloadOffset,
                    payloadLength, flags, streamId);
            case Http2FrameTypes.PING -> parsePing(frameBytes, payloadOffset,
                    payloadLength, flags, streamId);
            case Http2FrameTypes.GOAWAY -> parseGoAway(frameBytes, payloadOffset,
                    payloadLength, flags, streamId);
            case Http2FrameTypes.WINDOW_UPDATE -> parseWindowUpdate(frameBytes, payloadOffset,
                    payloadLength, flags, streamId);
            case Http2FrameTypes.CONTINUATION -> parseContinuation(frameBytes, payloadOffset,
                    payloadLength, flags, streamId);
            default -> new UnknownFrame(payloadLength, type, flags, streamId,
                    bytes(frameBytes, payloadOffset, payloadLength));
        };
    }

    private DataFrame parseData(byte[] bytes, int payloadOffset, int length,
                                int flags, int streamId) throws ParseErrorException {
        requireStream(streamId, Http2FrameTypes.DATA);
        PayloadRegion region = unpad(bytes, payloadOffset, length, flags,
                Http2FrameTypes.DATA, 0);
        return new DataFrame(length, flags, streamId,
                bytes(bytes, region.contentOffset(), region.contentLength()),
                region.paddingLength());
    }

    private HeadersFrame parseHeaders(byte[] bytes, int payloadOffset, int length,
                                      int flags, int streamId) throws ParseErrorException {
        requireStream(streamId, Http2FrameTypes.HEADERS);
        int priorityLength = hasFlag(flags, Http2Flags.PRIORITY) ? 5 : 0;
        PayloadRegion region = unpad(bytes, payloadOffset, length, flags,
                Http2FrameTypes.HEADERS, priorityLength);

        PriorityInfo priority = null;
        int fragmentOffset = region.contentOffset();
        int fragmentLength = region.contentLength();
        if (priorityLength != 0) {
            priority = readPriority(bytes, fragmentOffset, streamId,
                    Http2FrameTypes.HEADERS);
            fragmentOffset += 5;
            fragmentLength -= 5;
        }
        return new HeadersFrame(length, flags, streamId,
                bytes(bytes, fragmentOffset, fragmentLength), priority,
                region.paddingLength());
    }

    private PriorityFrame parsePriority(byte[] bytes, int payloadOffset, int length,
                                        int flags, int streamId) throws ParseErrorException {
        requireStream(streamId, Http2FrameTypes.PRIORITY);
        requireLength(length, 5, Http2FrameTypes.PRIORITY);
        return new PriorityFrame(length, flags, streamId,
                readPriority(bytes, payloadOffset, streamId, Http2FrameTypes.PRIORITY));
    }

    private RstStreamFrame parseRstStream(byte[] bytes, int payloadOffset, int length,
                                          int flags, int streamId) throws ParseErrorException {
        requireStream(streamId, Http2FrameTypes.RST_STREAM);
        requireLength(length, 4, Http2FrameTypes.RST_STREAM);
        return new RstStreamFrame(length, flags, streamId,
                unsigned32(bytes, payloadOffset));
    }

    private SettingsFrame parseSettings(byte[] bytes, int payloadOffset, int length,
                                        int flags, int streamId) throws ParseErrorException {
        requireConnection(streamId, Http2FrameTypes.SETTINGS);
        if (hasFlag(flags, Http2Flags.ACK) && length != 0) {
            throw error(ParseErrorReason.FRAME_SIZE_ERROR, HEADER_LENGTH,
                    Http2FrameTypes.SETTINGS,
                    "SETTINGS frame with ACK flag must have an empty payload");
        }
        if (length % 6 != 0) {
            throw error(ParseErrorReason.FRAME_SIZE_ERROR, HEADER_LENGTH,
                    Http2FrameTypes.SETTINGS,
                    "SETTINGS payload length must be a multiple of 6");
        }

        List<Setting> settings = new ArrayList<>(length / 6);
        for (int position = 0; position < length; position += 6) {
            int identifier = unsigned16(bytes, payloadOffset + position);
            long value = unsigned32(bytes, payloadOffset + position + 2);
            validateSetting(identifier, value, position);
            settings.add(new Setting(identifier, value));
        }
        return new SettingsFrame(length, flags, streamId, settings);
    }

    private PushPromiseFrame parsePushPromise(byte[] bytes, int payloadOffset, int length,
                                              int flags, int streamId)
            throws ParseErrorException {
        requireStream(streamId, Http2FrameTypes.PUSH_PROMISE);
        PayloadRegion region = unpad(bytes, payloadOffset, length, flags,
                Http2FrameTypes.PUSH_PROMISE, 4);
        int promisedStreamId = signed31(bytes, region.contentOffset());
        if (promisedStreamId == 0) {
            throw error(ParseErrorReason.INVALID_STREAM_ID,
                    region.contentOffset() - payloadOffset + HEADER_LENGTH,
                    Http2FrameTypes.PUSH_PROMISE,
                    "PUSH_PROMISE promised stream identifier must be non-zero");
        }
        return new PushPromiseFrame(length, flags, streamId, promisedStreamId,
                bytes(bytes, region.contentOffset() + 4, region.contentLength() - 4),
                region.paddingLength());
    }

    private PingFrame parsePing(byte[] bytes, int payloadOffset, int length,
                                int flags, int streamId) throws ParseErrorException {
        requireConnection(streamId, Http2FrameTypes.PING);
        requireLength(length, 8, Http2FrameTypes.PING);
        return new PingFrame(length, flags, streamId,
                bytes(bytes, payloadOffset, length));
    }

    private GoAwayFrame parseGoAway(byte[] bytes, int payloadOffset, int length,
                                    int flags, int streamId) throws ParseErrorException {
        requireConnection(streamId, Http2FrameTypes.GOAWAY);
        requireMinimumLength(length, 8, Http2FrameTypes.GOAWAY);
        return new GoAwayFrame(length, flags, streamId,
                signed31(bytes, payloadOffset), unsigned32(bytes, payloadOffset + 4),
                bytes(bytes, payloadOffset + 8, length - 8));
    }

    private WindowUpdateFrame parseWindowUpdate(byte[] bytes, int payloadOffset, int length,
                                                int flags, int streamId)
            throws ParseErrorException {
        requireLength(length, 4, Http2FrameTypes.WINDOW_UPDATE);
        int increment = signed31(bytes, payloadOffset);
        if (increment == 0) {
            throw error(ParseErrorReason.INVALID_PAYLOAD, HEADER_LENGTH,
                    Http2FrameTypes.WINDOW_UPDATE,
                    "WINDOW_UPDATE increment must be non-zero");
        }
        return new WindowUpdateFrame(length, flags, streamId, increment);
    }

    private ContinuationFrame parseContinuation(byte[] bytes, int payloadOffset, int length,
                                                int flags, int streamId)
            throws ParseErrorException {
        requireStream(streamId, Http2FrameTypes.CONTINUATION);
        return new ContinuationFrame(length, flags, streamId,
                bytes(bytes, payloadOffset, length));
    }

    private PayloadRegion unpad(byte[] bytes, int payloadOffset, int payloadLength,
                                int flags, int type, int requiredContentLength)
            throws ParseErrorException {
        int contentOffset = payloadOffset;
        int available = payloadLength;
        int paddingLength = 0;

        if (hasFlag(flags, Http2Flags.PADDED)) {
            if (available == 0) {
                throw error(ParseErrorReason.FRAME_SIZE_ERROR, HEADER_LENGTH, type,
                        "PADDED frame is missing its pad length field");
            }
            paddingLength = unsigned8(bytes, contentOffset);
            contentOffset++;
            available--;
            if (paddingLength > available) {
                throw error(ParseErrorReason.INVALID_PADDING, HEADER_LENGTH, type,
                        "Padding length exceeds the frame payload");
            }
            available -= paddingLength;
        }

        if (available < requiredContentLength) {
            throw error(ParseErrorReason.FRAME_SIZE_ERROR, HEADER_LENGTH, type,
                    "Frame payload is too short for mandatory fields");
        }
        return new PayloadRegion(contentOffset, available, paddingLength);
    }

    private PriorityInfo readPriority(byte[] bytes, int offset, int parentStreamId, int type)
            throws ParseErrorException {
        boolean exclusive = (bytes[offset] & 0x80) != 0;
        int dependency = signed31(bytes, offset);
        if (dependency == parentStreamId) {
            throw error(ParseErrorReason.INVALID_STREAM_ID, HEADER_LENGTH, type,
                    "A stream cannot depend on itself");
        }
        int weight = unsigned8(bytes, offset + 4) + 1;
        return new PriorityInfo(exclusive, dependency, weight);
    }

    private void validateSetting(int identifier, long value, int payloadPosition)
            throws ParseErrorException {
        boolean invalid = switch (identifier) {
            case 0x02 -> value > 1; // SETTINGS_ENABLE_PUSH
            case 0x04 -> value > 0x7fff_ffffL; // SETTINGS_INITIAL_WINDOW_SIZE
            case 0x05 -> value < MIN_MAX_FRAME_SIZE || value > MAX_FRAME_SIZE_LIMIT;
            default -> false;
        };
        if (invalid) {
            throw error(ParseErrorReason.INVALID_SETTING,
                    HEADER_LENGTH + payloadPosition + 2, Http2FrameTypes.SETTINGS,
                    "Invalid value " + value + " for setting 0x"
                            + Integer.toHexString(identifier));
        }
    }

    private static void requireStream(int streamId, int type) throws ParseErrorException {
        if (streamId == 0) {
            throw error(ParseErrorReason.INVALID_STREAM_ID, 5, type,
                    "Frame type 0x" + Integer.toHexString(type)
                            + " requires a non-zero stream identifier");
        }
    }

    private static void requireConnection(int streamId, int type) throws ParseErrorException {
        if (streamId != 0) {
            throw error(ParseErrorReason.INVALID_STREAM_ID, 5, type,
                    "Frame type 0x" + Integer.toHexString(type)
                            + " requires stream identifier zero");
        }
    }

    private static void requireLength(int actual, int expected, int type)
            throws ParseErrorException {
        if (actual != expected) {
            throw error(ParseErrorReason.FRAME_SIZE_ERROR, 0, type,
                    "Frame type 0x" + Integer.toHexString(type)
                            + " requires payload length " + expected + "; received " + actual);
        }
    }

    private static void requireMinimumLength(int actual, int minimum, int type)
            throws ParseErrorException {
        if (actual < minimum) {
            throw error(ParseErrorReason.FRAME_SIZE_ERROR, 0, type,
                    "Frame type 0x" + Integer.toHexString(type)
                            + " requires at least " + minimum + " payload bytes; received " + actual);
        }
    }

    private static boolean hasFlag(int flags, int flag) {
        return (flags & flag) != 0;
    }

    private static ByteSequence bytes(byte[] bytes, int offset, int length) {
        return ByteSequence.wrap(bytes, offset, length);
    }

    private static int unsigned8(byte[] bytes, int offset) {
        return Byte.toUnsignedInt(bytes[offset]);
    }

    private static int unsigned16(byte[] bytes, int offset) {
        return (unsigned8(bytes, offset) << 8) | unsigned8(bytes, offset + 1);
    }

    private static int unsigned24(byte[] bytes, int offset) {
        return (unsigned8(bytes, offset) << 16)
                | (unsigned8(bytes, offset + 1) << 8)
                | unsigned8(bytes, offset + 2);
    }

    private static int signed31(byte[] bytes, int offset) {
        return ((unsigned8(bytes, offset) & 0x7f) << 24)
                | (unsigned8(bytes, offset + 1) << 16)
                | (unsigned8(bytes, offset + 2) << 8)
                | unsigned8(bytes, offset + 3);
    }

    private static long unsigned32(byte[] bytes, int offset) {
        return ((long) unsigned8(bytes, offset) << 24)
                | ((long) unsigned8(bytes, offset + 1) << 16)
                | ((long) unsigned8(bytes, offset + 2) << 8)
                | unsigned8(bytes, offset + 3);
    }

    private static ParseErrorException error(ParseErrorReason reason, int offset,
                                             int frameType, String message) {
        return new ParseErrorException(reason, offset, frameType, message);
    }

    private record PayloadRegion(int contentOffset, int contentLength, int paddingLength) {
    }
}
