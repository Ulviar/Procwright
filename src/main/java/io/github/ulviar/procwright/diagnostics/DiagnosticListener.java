/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.diagnostics;

/**
 * Receives diagnostic events.
 *
 * <p>Listeners are invoked asynchronously on a best-effort basis. Listener failures are ignored by the runtime.
 * Diagnostics are observational and must not change command behavior.
 * Events may be dropped when delivery capacity is exhausted. Completion of a command or session does not wait for
 * outstanding diagnostic deliveries, so this callback is unsuitable for reliable auditing or process control.
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
     * <p>Keep delivery short. The event is immutable and may be retained, but retaining events is the listener's own
     * responsibility and is not bounded by Procwright.
     *
     * @param event diagnostic event
     */
    void onEvent(DiagnosticEvent event);
}
