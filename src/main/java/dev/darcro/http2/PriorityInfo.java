package dev.darcro.http2;

/** Deprecated RFC 7540 priority information retained by RFC 9113 frame layouts. */
public record PriorityInfo(boolean exclusive, int streamDependency, int weight) {
}
