/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.diagnostics.DiagnosticsOptions;
import io.github.ulviar.procwright.session.StreamListener;
import io.github.ulviar.procwright.session.StreamStdinPolicy;
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
