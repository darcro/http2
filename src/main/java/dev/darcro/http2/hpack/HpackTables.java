package dev.darcro.http2.hpack;

import java.nio.charset.StandardCharsets;

final class HpackTables {
    private static final String[][] STATIC_VALUES = {
            {":authority", ""}, {":method", "GET"}, {":method", "POST"},
            {":path", "/"}, {":path", "/index.html"}, {":scheme", "http"},
            {":scheme", "https"}, {":status", "200"}, {":status", "204"},
            {":status", "206"}, {":status", "304"}, {":status", "400"},
            {":status", "404"}, {":status", "500"}, {"accept-charset", ""},
            {"accept-encoding", "gzip, deflate"}, {"accept-language", ""},
            {"accept-ranges", ""}, {"accept", ""},
            {"access-control-allow-origin", ""}, {"age", ""}, {"allow", ""},
            {"authorization", ""}, {"cache-control", ""},
            {"content-disposition", ""}, {"content-encoding", ""},
            {"content-language", ""}, {"content-length", ""},
            {"content-location", ""}, {"content-range", ""},
            {"content-type", ""}, {"cookie", ""}, {"date", ""}, {"etag", ""},
            {"expect", ""}, {"expires", ""}, {"from", ""}, {"host", ""},
            {"if-match", ""}, {"if-modified-since", ""}, {"if-none-match", ""},
            {"if-range", ""}, {"if-unmodified-since", ""},
            {"last-modified", ""}, {"link", ""}, {"location", ""},
            {"max-forwards", ""}, {"proxy-authenticate", ""},
            {"proxy-authorization", ""}, {"range", ""}, {"referer", ""},
            {"refresh", ""}, {"retry-after", ""}, {"server", ""},
            {"set-cookie", ""}, {"strict-transport-security", ""},
            {"transfer-encoding", ""}, {"user-agent", ""}, {"vary", ""},
            {"via", ""}, {"www-authenticate", ""}
    };

    static final Entry[] STATIC_TABLE = new Entry[STATIC_VALUES.length];

    static {
        for (int i = 0; i < STATIC_VALUES.length; i++) {
            STATIC_TABLE[i] = new Entry(ascii(STATIC_VALUES[i][0]),
                    ascii(STATIC_VALUES[i][1]));
        }
    }

    private HpackTables() {
    }

    private static byte[] ascii(String value) {
        return value.getBytes(StandardCharsets.US_ASCII);
    }

    record Entry(byte[] name, byte[] value) {
        int size() {
            return name.length + value.length + 32;
        }
    }
}
