package com.github.ulviar.icli;

import java.time.Duration;
import java.util.Objects;

record StreamExecutionPlan(
        SessionExecutionPlan sessionPlan,
        Duration timeout,
        StreamStdinPolicy stdinPolicy,
        int diagnosticLimit,
        StreamListener listener) {

    StreamExecutionPlan {
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
    }
}
