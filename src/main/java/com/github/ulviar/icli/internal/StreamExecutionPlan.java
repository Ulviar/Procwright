package com.github.ulviar.icli.internal;

import com.github.ulviar.icli.diagnostics.DiagnosticsOptions;
import com.github.ulviar.icli.session.StreamListener;
import com.github.ulviar.icli.session.StreamStdinPolicy;
import java.time.Duration;
import java.util.Objects;

public record StreamExecutionPlan(
        SessionExecutionPlan sessionPlan,
        Duration timeout,
        StreamStdinPolicy stdinPolicy,
        int diagnosticLimit,
        StreamListener listener,
        DiagnosticsOptions diagnosticsOptions) {

    public StreamExecutionPlan {
        Objects.requireNonNull(sessionPlan, "sessionPlan");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        Objects.requireNonNull(stdinPolicy, "stdinPolicy");
        if (diagnosticLimit <= 0) {
            throw new IllegalArgumentException("diagnosticLimit must be positive");
        }
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(diagnosticsOptions, "diagnosticsOptions");
    }
}
