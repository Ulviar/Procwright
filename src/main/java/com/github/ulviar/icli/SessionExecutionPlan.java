package com.github.ulviar.icli;

import java.nio.charset.Charset;
import java.time.Duration;
import java.util.Objects;

record SessionExecutionPlan(
        LaunchPlan launchPlan, ShutdownPolicy shutdownPolicy, Duration idleTimeout, Charset charset) {

    SessionExecutionPlan {
        Objects.requireNonNull(launchPlan, "launchPlan");
        Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
        Objects.requireNonNull(idleTimeout, "idleTimeout");
        if (idleTimeout.isNegative()) {
            throw new IllegalArgumentException("idleTimeout must not be negative");
        }
        Objects.requireNonNull(charset, "charset");
    }
}
