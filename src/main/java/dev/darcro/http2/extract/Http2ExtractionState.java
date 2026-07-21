package dev.darcro.http2.extract;

/** Current synchronization state of an HTTP/2 frame extractor. */
public enum Http2ExtractionState {
    PROBING_PREFACE,
    SEARCHING,
    SYNCHRONIZED,
    FINISHED
}
