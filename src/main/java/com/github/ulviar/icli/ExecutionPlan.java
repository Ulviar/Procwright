package com.github.ulviar.icli;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

record ExecutionPlan(
        LaunchPlan launchPlan,
        CapturePolicy.Bounded capturePolicy,
        ShutdownPolicy shutdownPolicy,
        Duration timeout,
        Charset charset,
        StdinPolicy stdin,
        DiagnosticsOptions diagnosticsOptions) {

    ExecutionPlan {
        Objects.requireNonNull(launchPlan, "launchPlan");
        Objects.requireNonNull(capturePolicy, "capturePolicy");
        Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        Objects.requireNonNull(charset, "charset");
        Objects.requireNonNull(stdin, "stdin");
        Objects.requireNonNull(diagnosticsOptions, "diagnosticsOptions");
    }

    LaunchMode launchMode() {
        return launchPlan.launchMode();
    }

    java.util.List<String> command() {
        return launchPlan.command();
    }

    Optional<Path> workingDirectory() {
        return launchPlan.workingDirectory();
    }

    Map<String, String> environment() {
        return launchPlan.environment();
    }

    OutputMode outputMode() {
        return launchPlan.outputMode();
    }

    TerminalPolicy terminalPolicy() {
        return launchPlan.terminalPolicy();
    }
}
