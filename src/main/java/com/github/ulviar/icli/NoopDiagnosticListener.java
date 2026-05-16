package com.github.ulviar.icli;

enum NoopDiagnosticListener implements DiagnosticListener {
    INSTANCE;

    @Override
    public void onEvent(DiagnosticEvent event) {
        // Intentionally ignored.
    }
}
