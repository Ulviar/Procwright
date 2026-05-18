package com.github.ulviar.icli.diagnostics;

/**
 * Receives diagnostic events.
 *
 * <p>Listeners are invoked asynchronously on a best-effort basis. Listener failures are ignored by the runtime.
 * Diagnostics are observational and must not change command behavior.
 */
@FunctionalInterface
public interface DiagnosticListener {

    /**
     * Returns a listener that ignores all events.
     *
     * @return no-op listener
     */
    static DiagnosticListener noop() {
        return NoopDiagnosticListener.INSTANCE;
    }

    /**
     * Handles one diagnostic event.
     *
     * @param event diagnostic event
     */
    void onEvent(DiagnosticEvent event);
}
