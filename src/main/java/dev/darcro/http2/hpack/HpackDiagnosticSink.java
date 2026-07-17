package dev.darcro.http2.hpack;

/** Receives passive-analysis diagnostics synchronously. */
@FunctionalInterface
public interface HpackDiagnosticSink {
    void accept(HpackDiagnostic diagnostic);

    static HpackDiagnosticSink noop() {
        return diagnostic -> { };
    }
}
