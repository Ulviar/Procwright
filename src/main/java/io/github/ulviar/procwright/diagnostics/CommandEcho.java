/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.diagnostics;

import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Redaction-friendly command echo used by diagnostics.
 *
 * <p>The echo deliberately does not expose argument values or environment values. Arguments can contain secrets, so
 * runtime-generated echoes expose the executable and argument count rather than argument contents. A shell command
 * is represented by its shell executable, not the interpreted command text. Working directories and environment names
 * remain visible and may still be sensitive in some applications.
 *
 * <p>The record snapshots the environment-name list. Its public constructor does not redact caller-supplied strings;
 * callers constructing echoes must avoid putting secrets into those fields.
 *
 * @param executable executable token
 * @param argumentCount non-negative number of command arguments after the executable
 * @param workingDirectory working directory when configured
 * @param environmentNames environment override names without values
 * @param outputMode output routing mode
 * @param terminalPolicy terminal policy
 */
public record CommandEcho(
        String executable,
        int argumentCount,
        Optional<Path> workingDirectory,
        List<String> environmentNames,
        OutputMode outputMode,
        TerminalPolicy terminalPolicy) {

    private static final CommandEcho EMPTY =
            new CommandEcho("", 0, Optional.empty(), List.of(), OutputMode.SEPARATE, TerminalPolicy.DISABLED);

    /**
     * Validates and snapshots a command echo.
     *
     * @param executable executable token
     * @param argumentCount non-negative number of command arguments after the executable
     * @param workingDirectory working directory when configured
     * @param environmentNames environment override names without values
     * @param outputMode output routing mode
     * @param terminalPolicy terminal policy
     * @throws IllegalArgumentException if {@code argumentCount} is negative
     */
    public CommandEcho {
        Objects.requireNonNull(executable, "executable");
        if (argumentCount < 0) {
            throw new IllegalArgumentException("argumentCount must not be negative");
        }
        workingDirectory = Objects.requireNonNull(workingDirectory, "workingDirectory");
        environmentNames = List.copyOf(environmentNames);
        Objects.requireNonNull(outputMode, "outputMode");
        Objects.requireNonNull(terminalPolicy, "terminalPolicy");
    }

    /**
     * Returns an empty echo for events without a bound command.
     *
     * <p>The echo has an empty executable and environment-name list, zero arguments, no working directory,
     * separate output, and a disabled terminal policy.
     *
     * @return empty command echo
     */
    public static CommandEcho empty() {
        return EMPTY;
    }
}
