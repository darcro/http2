package dev.darcro.http2.hpack;

/** The wire source from which a decoded header name or value was obtained. */
public enum HpackValueSource {
    LITERAL,
    STATIC_TABLE,
    DYNAMIC_TABLE
}
