package com.github.ulviar.icli.comparison;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Objects;

record CommandRequest(
        List<String> command, Map<String, String> environment, Path workingDirectory, byte[] stdin, Duration timeout) {

    CommandRequest {
        command = List.copyOf(command);
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        environment = Map.copyOf(environment);
        stdin = stdin == null ? new byte[0] : stdin.clone();
        Objects.requireNonNull(timeout, "timeout");
    }

    static CommandRequest of(List<String> command, Duration timeout) {
        return new CommandRequest(command, Map.of(), null, new byte[0], timeout);
    }

    CommandRequest withEnvironment(String name, String value) {
        java.util.LinkedHashMap<String, String> updated = new java.util.LinkedHashMap<>(environment);
        updated.put(name, value);
        return new CommandRequest(command, updated, workingDirectory, stdin, timeout);
    }

    CommandRequest withStdin(byte[] input) {
        return new CommandRequest(command, environment, workingDirectory, input, timeout);
    }
}
