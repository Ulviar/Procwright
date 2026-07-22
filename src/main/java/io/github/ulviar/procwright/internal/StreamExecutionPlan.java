/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.session.StreamListener;
import java.time.Duration;
import java.util.Objects;

public record StreamExecutionPlan(
        SessionExecutionPlan sessionPlan,
        Duration timeout,
        int diagnosticLimit,
        StreamListener listener,
        DiagnosticsSettings diagnostics) {

    public StreamExecutionPlan {
        Objects.requireNonNull(sessionPlan, "sessionPlan");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        if (diagnosticLimit <= 0) {
            throw new IllegalArgumentException("diagnosticLimit must be positive");
        }
        Objects.requireNonNull(listener, "listener");
        Objects.requireNonNull(diagnostics, "diagnostics");
    }
}
