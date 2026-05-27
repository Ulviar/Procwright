package io.github.ulviar.procwright.diagnostics;

enum NoopDiagnosticTranscriptSink implements DiagnosticTranscriptSink {
    INSTANCE;

    @Override
    public void record(DiagnosticEvent event) {
        // Intentionally ignored.
    }
}
