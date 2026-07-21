package dev.darcro.http2.extract;

/** Evidence used to establish the boundary of an extracted frame sequence. */
public enum FrameBoundaryProvenance {
    CONNECTION_PREFACE,
    MIDSTREAM_STANDARD_FRAME_SEQUENCE
}
