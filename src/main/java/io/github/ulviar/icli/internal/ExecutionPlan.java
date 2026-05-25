package io.github.ulviar.icli.internal;

import io.github.ulviar.icli.command.CapturePolicy;
import io.github.ulviar.icli.command.CharsetPolicy;
import io.github.ulviar.icli.command.OutputMode;
import io.github.ulviar.icli.command.ShutdownPolicy;
import io.github.ulviar.icli.diagnostics.DiagnosticsOptions;
import io.github.ulviar.icli.terminal.TerminalPolicy;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record ExecutionPlan(
        LaunchPlan launchPlan,
        CapturePolicy.Bounded capturePolicy,
        ShutdownPolicy shutdownPolicy,
        Duration timeout,
        CharsetPolicy charsetPolicy,
        StdinPolicy stdin,
        DiagnosticsOptions diagnosticsOptions) {

    public ExecutionPlan {
        Objects.requireNonNull(launchPlan, "launchPlan");
        Objects.requireNonNull(capturePolicy, "capturePolicy");
        Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        Objects.requireNonNull(charsetPolicy, "charsetPolicy");
        Objects.requireNonNull(stdin, "stdin");
        Objects.requireNonNull(diagnosticsOptions, "diagnosticsOptions");
    }

    public Charset charset() {
        return charsetPolicy.charset();
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
