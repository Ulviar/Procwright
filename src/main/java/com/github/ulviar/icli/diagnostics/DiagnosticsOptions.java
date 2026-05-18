package com.github.ulviar.icli.diagnostics;

import java.util.Objects;

/**
 * Observability hooks shared by command scenarios.
 */
public final class DiagnosticsOptions {

    private static final DiagnosticsOptions DEFAULTS =
            new DiagnosticsOptions(DiagnosticListener.noop(), DiagnosticTranscriptSink.noop());

    private final DiagnosticListener listener;
    private final DiagnosticTranscriptSink transcriptSink;

    /**
     * Creates diagnostics options.
     *
     * @param listener event listener
     * @param transcriptSink optional transcript sink
     */
    public DiagnosticsOptions(DiagnosticListener listener, DiagnosticTranscriptSink transcriptSink) {
        this.listener = Objects.requireNonNull(listener, "listener");
        this.transcriptSink = Objects.requireNonNull(transcriptSink, "transcriptSink");
    }

    /**
     * Returns default no-op diagnostics options.
     *
     * @return default diagnostics options
     */
    public static DiagnosticsOptions defaults() {
        return DEFAULTS;
    }

    /**
     * Returns a copy with a different diagnostic listener.
     *
     * @param listener event listener
     * @return updated options
     */
    public DiagnosticsOptions withListener(DiagnosticListener listener) {
        return new DiagnosticsOptions(listener, transcriptSink);
    }

    /**
     * Returns a copy with a different transcript sink.
     *
     * @param transcriptSink transcript sink
     * @return updated options
     */
    public DiagnosticsOptions withTranscriptSink(DiagnosticTranscriptSink transcriptSink) {
        return new DiagnosticsOptions(listener, transcriptSink);
    }

    /**
     * Returns the configured diagnostic listener.
     *
     * @return diagnostic listener
     */
    public DiagnosticListener listener() {
        return listener;
    }

    /**
     * Returns the configured transcript sink.
     *
     * @return transcript sink
     */
    public DiagnosticTranscriptSink transcriptSink() {
        return transcriptSink;
    }

    /**
     * Returns whether diagnostics listener delivery is enabled.
     *
     * @return {@code true} when the listener is not the default no-op listener
     */
    public boolean listenerEnabled() {
        return listener != NoopDiagnosticListener.INSTANCE;
    }

    /**
     * Returns whether transcript sink delivery is enabled.
     *
     * @return {@code true} when the sink is not the default no-op sink
     */
    public boolean transcriptSinkEnabled() {
        return transcriptSink != NoopDiagnosticTranscriptSink.INSTANCE;
    }

    /**
     * Returns whether at least one diagnostics delivery hook is enabled.
     *
     * @return {@code true} when listener or transcript sink delivery is enabled
     */
    public boolean enabled() {
        return listenerEnabled() || transcriptSinkEnabled();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof DiagnosticsOptions that)) {
            return false;
        }
        return listener.equals(that.listener) && transcriptSink.equals(that.transcriptSink);
    }

    @Override
    public int hashCode() {
        return Objects.hash(listener, transcriptSink);
    }
}
