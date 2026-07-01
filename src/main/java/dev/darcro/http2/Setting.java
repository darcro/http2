package dev.darcro.http2;

/** A SETTINGS parameter. Value is an unsigned 32-bit integer stored in a long. */
public record Setting(int identifier, long value) {
}
