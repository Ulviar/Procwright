/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.diagnostics;

/**
 * Optional sink for persisting diagnostic event transcripts.
 *
 * <p>Sinks are invoked asynchronously on a best-effort basis. Sink failures are ignored by the runtime. Diagnostics are
 * observational and must not change command behavior.
 *
 * <p>One command or session lifecycle serializes calls to its transcript sink in submission order. Separate lifecycles
 * use independent delivery queues, so reusing one sink in an immutable scenario {@code Draft} can invoke that same
 * instance concurrently when terminal calls or pool workers overlap. A shared sink must be thread-safe; otherwise, use
 * separate Draft branches with separate sink instances. Transcript-sink and listener delivery also use independent
 * queues and may overlap.
 */
@FunctionalInterface
public interface DiagnosticTranscriptSink {

    /**
     * Returns a sink that ignores all events.
     *
     * @return no-op sink
     */
    static DiagnosticTranscriptSink noop() {
        return NoopDiagnosticTranscriptSink.INSTANCE;
    }

    /**
     * Records one diagnostic event.
     *
     * @param event diagnostic event
     */
    void record(DiagnosticEvent event);
}
