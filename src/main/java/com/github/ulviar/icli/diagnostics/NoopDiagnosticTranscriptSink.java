package com.github.ulviar.icli.diagnostics;

enum NoopDiagnosticTranscriptSink implements DiagnosticTranscriptSink {
    INSTANCE;

    @Override
    public void record(DiagnosticEvent event) {
        // Intentionally ignored.
    }
}
