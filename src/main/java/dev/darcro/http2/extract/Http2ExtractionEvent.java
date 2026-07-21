package dev.darcro.http2.extract;

/** An ordered event emitted while extracting HTTP/2 frames from payload bytes. */
public sealed interface Http2ExtractionEvent permits Http2ConnectionPrefaceExtracted,
        Http2FrameExtracted, Http2FrameCandidateRejected, Http2ExtractionDiagnostic {

    /** Zero-based logical offset in the supplied payload stream. */
    long streamOffset();
}
