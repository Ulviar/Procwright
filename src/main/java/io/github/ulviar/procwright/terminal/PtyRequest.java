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
 * <p>The command and environment are immutable snapshots. The first command element is the executable and the
 * remaining elements are literal arguments; the provider must preserve those boundaries. An explicit shell command
 * has already been expanded to its shell executable and arguments before this request is created.
 *
 * <p>The environment contains configured overrides, not necessarily the full parent environment. The provider applies
 * {@link #environmentPolicy()} before the overrides. File paths are retained without opening or validating them.
 *
 * @param command non-empty direct argv command to run inside the terminal
 * @param workingDirectory optional working directory for the terminal child
 * @param environmentPolicy child environment assembly policy
 * @param environment environment overrides for the terminal child; providers must not place these values in a
 *     transport wrapper environment or diagnostic output before the final child launch
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
     * <p>Construction checks non-null components and a non-empty command. Provider-specific command, environment,
     * encoding, and payload-size checks belong to {@link PtyProvider#start(PtyRequest)}.
     *
     * @param command non-empty direct argv command to run inside the terminal
     * @param workingDirectory optional working directory for the terminal child
     * @param environmentPolicy child environment assembly policy
     * @param environment environment overrides for the terminal child
     * @param terminalSize requested terminal dimensions
     * @throws IllegalArgumentException if {@code command} is empty
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
