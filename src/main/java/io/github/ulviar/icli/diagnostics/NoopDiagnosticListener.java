package io.github.ulviar.icli.diagnostics;

enum NoopDiagnosticListener implements DiagnosticListener {
    INSTANCE;

    @Override
    public void onEvent(DiagnosticEvent event) {
        // Intentionally ignored.
    }
}
