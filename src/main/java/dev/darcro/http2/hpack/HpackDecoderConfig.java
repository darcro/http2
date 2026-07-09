package dev.darcro.http2.hpack;

/** Resource limits for an HPACK decoder. */
public record HpackDecoderConfig(int maxDynamicTableCapacity,
                                 int maxEncodedHeaderBlockSize,
                                 int maxDecodedHeaderListSize,
                                 HpackDynamicTableRecoveryPolicy
                                         dynamicTableRecoveryPolicy) {
    public static final int MIN_DYNAMIC_TABLE_CAPACITY = 4_096;
    public static final int STRICT_DYNAMIC_TABLE_CAPACITY = 4_096;
    public static final int STRICT_HEADER_BLOCK_SIZE = 65_536;
    public static final int STRICT_HEADER_LIST_SIZE = 65_536;
    public static final int DEFAULT_DYNAMIC_TABLE_CAPACITY = 0x00ff_ffff;
    public static final int DEFAULT_HEADER_BLOCK_SIZE = 0x00ff_ffff;
    public static final int DEFAULT_HEADER_LIST_SIZE = 0x00ff_ffff;
    public static final HpackDecoderConfig DEFAULT_CONFIG = new HpackDecoderConfig(
            DEFAULT_DYNAMIC_TABLE_CAPACITY,
            DEFAULT_HEADER_BLOCK_SIZE,
            DEFAULT_HEADER_LIST_SIZE,
            HpackDynamicTableRecoveryPolicy.SKIP_MISSING);
    public static final HpackDecoderConfig STRICT_CONFIG = new HpackDecoderConfig(
            STRICT_DYNAMIC_TABLE_CAPACITY,
            STRICT_HEADER_BLOCK_SIZE,
            STRICT_HEADER_LIST_SIZE,
            HpackDynamicTableRecoveryPolicy.FAIL_ON_MISSING);

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

    public static HpackDecoderConfig strictDefaults() {
        return STRICT_CONFIG;
    }
}
