package dev.darcro.extract;

/** Categories of payload ranges not emitted as synchronized HTTP/2 frames. */
public enum Http2ExtractionDiagnosticReason {
    BYTES_SKIPPED,
    FRAME_SIZE_LIMIT,
    TRAILING_INCOMPLETE_DATA
}
