/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.diagnostics;

/**
 * Receives diagnostic events.
 *
 * <p>Listeners are invoked asynchronously on a best-effort basis. Listener failures are ignored by the runtime.
 * Diagnostics are observational and must not change command behavior.
 *
 * <p>One command or session lifecycle serializes calls to its listener in submission order. Separate lifecycles use
 * independent delivery queues, so reusing one listener in an immutable scenario {@code Draft} can invoke that same
 * instance concurrently when terminal calls or pool workers overlap. A shared listener must be thread-safe; otherwise,
 * use separate Draft branches with separate listener instances. Listener and transcript-sink delivery also use
 * independent queues and may overlap.
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
