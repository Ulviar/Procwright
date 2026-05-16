package com.github.ulviar.icli;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

record LaunchPlan(
        LaunchMode launchMode,
        List<String> command,
        Optional<Path> workingDirectory,
        Map<String, String> environment,
        OutputMode outputMode,
        TerminalPolicy terminalPolicy) {

    LaunchPlan {
        Objects.requireNonNull(launchMode, "launchMode");
        command = List.copyOf(command);
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        environment = Map.copyOf(environment);
        Objects.requireNonNull(outputMode, "outputMode");
        Objects.requireNonNull(terminalPolicy, "terminalPolicy");
    }
}
