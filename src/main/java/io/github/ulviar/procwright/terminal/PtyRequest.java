/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.terminal;

import io.github.ulviar.procwright.command.EnvironmentPolicy;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Fully resolved request passed to a PTY provider.
 *
 * @param command direct argv command to run inside the terminal
 * @param workingDirectory optional working directory for the provider process
 * @param environmentPolicy child environment assembly policy
 * @param environment environment overrides for the provider process
 * @param terminalSize requested terminal dimensions
 */
public record PtyRequest(
        List<String> command,
        Optional<Path> workingDirectory,
        EnvironmentPolicy environmentPolicy,
        Map<String, String> environment,
        TerminalSize terminalSize) {

    /**
     * Validates and snapshots the request.
     *
     * @param command direct argv command to run inside the terminal
     * @param workingDirectory optional working directory for the provider process
     * @param environmentPolicy child environment assembly policy
     * @param environment environment overrides for the provider process
     * @param terminalSize requested terminal dimensions
     */
    public PtyRequest {
        command = List.copyOf(command);
        if (command.isEmpty()) {
            throw new IllegalArgumentException("command must not be empty");
        }
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        Objects.requireNonNull(environmentPolicy, "environmentPolicy");
        environment = Map.copyOf(environment);
        terminalSize = Objects.requireNonNull(terminalSize, "terminalSize");
    }
}
