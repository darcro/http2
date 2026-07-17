package dev.darcro.http2.hpack;

/** Resource limits for an HPACK decoder. */
public record HpackDecoderConfig(int maxDynamicTableCapacity,
                                 int maxEncodedHeaderBlockSize,
                                 int maxDecodedHeaderListSize) {
    public static final int MIN_DYNAMIC_TABLE_CAPACITY = 4_096;
    public static final int DEFAULT_DYNAMIC_TABLE_CAPACITY = 0x00ff_ffff;
    public static final int DEFAULT_HEADER_BLOCK_SIZE = 0x00ff_ffff;
    public static final int DEFAULT_HEADER_LIST_SIZE = 0x00ff_ffff;
    public static final HpackDecoderConfig DEFAULT_CONFIG = new HpackDecoderConfig(
            DEFAULT_DYNAMIC_TABLE_CAPACITY,
            DEFAULT_HEADER_BLOCK_SIZE,
            DEFAULT_HEADER_LIST_SIZE);

    public HpackDecoderConfig {
        if (maxDynamicTableCapacity < MIN_DYNAMIC_TABLE_CAPACITY) {
            throw new IllegalArgumentException("maxDynamicTableCapacity must be at least 4096");
        }
        if (maxEncodedHeaderBlockSize <= 0) {
            throw new IllegalArgumentException("maxEncodedHeaderBlockSize must be positive");
        }
        if (maxDecodedHeaderListSize <= 0) {
            throw new IllegalArgumentException("maxDecodedHeaderListSize must be positive");
        }
    }

    public static HpackDecoderConfig defaults() {
        return DEFAULT_CONFIG;
    }

}
