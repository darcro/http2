package dev.darcro.http2.frame;

/** Shared bounded formatting for frame {@code toString()} implementations. */
final class FrameText {
    private static final int MAX_BINARY_BYTES = 64;
    private static final char[] HEX = "0123456789abcdef".toCharArray();

    private FrameText() {
    }

    static StringBuilder start(String name, Http2Frame frame) {
        return new StringBuilder(name).append("[length=").append(frame.length())
                .append(", type=").append(hexByte(frame.type()))
                .append(", flags=").append(hexByte(frame.flags()))
                .append(", streamId=").append(frame.streamId());
    }

    static String hex(ByteSequence bytes) {
        if (bytes == null) {
            return "null";
        }
        int displayed = Math.min(bytes.length(), MAX_BINARY_BYTES);
        StringBuilder result = new StringBuilder(displayed * 2 + 24);
        for (int i = 0; i < displayed; i++) {
            int value = bytes.unsignedByteAt(i);
            result.append(HEX[value >>> 4]).append(HEX[value & 0x0f]);
        }
        if (displayed < bytes.length()) {
            result.append("...(+").append(bytes.length() - displayed).append(" bytes)");
        }
        return result.toString();
    }

    static String hexByte(int value) {
        return new String(new char[]{'0', 'x', HEX[(value >>> 4) & 0x0f],
                HEX[value & 0x0f]});
    }

    static String hexUnsignedInt(long value) {
        StringBuilder result = new StringBuilder("0x");
        for (int shift = 28; shift >= 0; shift -= 4) {
            result.append(HEX[(int) (value >>> shift) & 0x0f]);
        }
        return result.toString();
    }
}
