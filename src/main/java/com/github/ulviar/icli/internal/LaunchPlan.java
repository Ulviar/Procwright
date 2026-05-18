package com.github.ulviar.icli.internal;

import com.github.ulviar.icli.command.EnvironmentPolicy;
import com.github.ulviar.icli.command.OutputMode;
import com.github.ulviar.icli.terminal.TerminalPolicy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

public record LaunchPlan(
        LaunchMode launchMode,
        List<String> command,
        Optional<Path> workingDirectory,
        EnvironmentPolicy environmentPolicy,
        Map<String, String> environment,
        OutputMode outputMode,
        TerminalPolicy terminalPolicy) {

    public LaunchPlan {
        Objects.requireNonNull(launchMode, "launchMode");
        command = List.copyOf(command);
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(environmentPolicy, "environmentPolicy");
        environment = Map.copyOf(environment);
        Objects.requireNonNull(outputMode, "outputMode");
        Objects.requireNonNull(terminalPolicy, "terminalPolicy");
    }
}
