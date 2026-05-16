package com.github.ulviar.icli;

import java.nio.charset.Charset;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

record ExecutionPlan(
        LaunchMode launchMode,
        List<String> command,
        Optional<Path> workingDirectory,
        Map<String, String> environment,
        CapturePolicy.Bounded capturePolicy,
        ShutdownPolicy shutdownPolicy,
        Duration timeout,
        Charset charset,
        OutputMode outputMode,
        CommandInput stdin,
        TerminalPolicy terminalPolicy) {

    ExecutionPlan {
        Objects.requireNonNull(launchMode, "launchMode");
        command = List.copyOf(command);
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        environment = Map.copyOf(environment);
        Objects.requireNonNull(capturePolicy, "capturePolicy");
        Objects.requireNonNull(shutdownPolicy, "shutdownPolicy");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        Objects.requireNonNull(charset, "charset");
        Objects.requireNonNull(outputMode, "outputMode");
        Objects.requireNonNull(stdin, "stdin");
        Objects.requireNonNull(terminalPolicy, "terminalPolicy");
    }
}
