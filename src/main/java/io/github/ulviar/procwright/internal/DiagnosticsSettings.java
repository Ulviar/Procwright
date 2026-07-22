/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.diagnostics.DiagnosticListener;
import io.github.ulviar.procwright.diagnostics.DiagnosticTranscriptSink;
import java.util.Objects;

/** Immutable diagnostics wiring used by execution plans. */
public record DiagnosticsSettings(DiagnosticListener listener, DiagnosticTranscriptSink transcriptSink) {

    private static final DiagnosticsSettings DISABLED =
            new DiagnosticsSettings(DiagnosticListener.noop(), DiagnosticTranscriptSink.noop());

    public DiagnosticsSettings {
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(transcriptSink, "transcriptSink");
    }

    public static DiagnosticsSettings disabled() {
        return DISABLED;
    }

    public DiagnosticsSettings withListener(DiagnosticListener listener) {
        return new DiagnosticsSettings(listener, transcriptSink);
    }

    public DiagnosticsSettings withTranscriptSink(DiagnosticTranscriptSink transcriptSink) {
        return new DiagnosticsSettings(listener, transcriptSink);
    }

    public boolean listenerEnabled() {
        return listener != DiagnosticListener.noop();
    }

    public boolean transcriptSinkEnabled() {
        return transcriptSink != DiagnosticTranscriptSink.noop();
    }

    public boolean enabled() {
        return listenerEnabled() || transcriptSinkEnabled();
    }
}
