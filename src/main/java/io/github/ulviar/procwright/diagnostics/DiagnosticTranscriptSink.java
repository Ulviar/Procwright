package io.github.ulviar.procwright.diagnostics;

/**
 * Optional sink for persisting diagnostic event transcripts.
 *
 * <p>Sinks are invoked asynchronously on a best-effort basis. Sink failures are ignored by the runtime. Diagnostics are
 * observational and must not change command behavior.
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
