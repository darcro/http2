package dev.darcro.http2.hpack;

/** Resource limits for an HPACK decoder. */
public record HpackDecoderConfig(int maxDynamicTableCapacity,
                                 int maxEncodedHeaderBlockSize,
                                 int maxDecodedHeaderListSize,
                                 HpackDynamicTableRecoveryPolicy
                                         dynamicTableRecoveryPolicy) {
    public static final int DEFAULT_DYNAMIC_TABLE_CAPACITY = 4_096;
    public static final int DEFAULT_HEADER_BLOCK_SIZE = 65_536;
    public static final int DEFAULT_HEADER_LIST_SIZE = 65_536;
    public static final HpackDecoderConfig DEFAULT_CONFIG = new HpackDecoderConfig(
            DEFAULT_DYNAMIC_TABLE_CAPACITY,
            DEFAULT_HEADER_BLOCK_SIZE,
            DEFAULT_HEADER_LIST_SIZE,
            HpackDynamicTableRecoveryPolicy.SKIP_MISSING);

    public HpackDecoderConfig(int maxDynamicTableCapacity,
                              int maxEncodedHeaderBlockSize,
                              int maxDecodedHeaderListSize) {
        this(maxDynamicTableCapacity, maxEncodedHeaderBlockSize,
                maxDecodedHeaderListSize,
                HpackDynamicTableRecoveryPolicy.SKIP_MISSING);
    }

    public HpackDecoderConfig {
        if (dynamicTableRecoveryPolicy == null) {
            throw new NullPointerException("dynamicTableRecoveryPolicy");
        }
        if (maxDynamicTableCapacity < DEFAULT_DYNAMIC_TABLE_CAPACITY) {
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
