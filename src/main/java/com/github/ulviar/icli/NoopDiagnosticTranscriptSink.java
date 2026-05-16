package com.github.ulviar.icli;

enum NoopDiagnosticTranscriptSink implements DiagnosticTranscriptSink {
    INSTANCE;

    @Override
    public void record(DiagnosticEvent event) {
        // Intentionally ignored.
    }
}
