/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.diagnostics;

/**
 * Optional sink for persisting a sequence of structured lifecycle events.
 *
 * <p>The sink receives {@link DiagnosticEvent}s, not stdin/stdout/stderr text or bounded output transcript snapshots.
 * For command output use {@link io.github.ulviar.procwright.command.CommandResult}; for streaming output use
 * {@link io.github.ulviar.procwright.session.StreamListener} or
 * {@link io.github.ulviar.procwright.session.StreamSession#diagnostics()}.
 *
 * <p>Sinks are invoked asynchronously on a best-effort basis. Sink failures are ignored by the runtime. Diagnostics are
 * observational and must not change command behavior.
 * Events may be dropped when delivery capacity is exhausted, and completion of a command or session does not flush
 * pending deliveries. This sink is not a durable audit log. The sink owns any storage and retention policy it uses.
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
     * <p>The event is immutable and may be retained. Procwright does not close or flush caller-owned storage.
     *
     * @param event diagnostic event
     */
    void record(DiagnosticEvent event);
}
