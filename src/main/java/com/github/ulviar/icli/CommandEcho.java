package com.github.ulviar.icli;

import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Redaction-friendly command echo used by diagnostics.
 *
 * <p>The echo deliberately does not expose argument values or environment values. Arguments can contain secrets, so
 * diagnostics expose only the executable and argument count by default.
 *
 * @param executable executable token
 * @param argumentCount number of command arguments after the executable
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
     * @param argumentCount number of command arguments after the executable
     * @param workingDirectory working directory when configured
     * @param environmentNames environment override names without values
     * @param outputMode output routing mode
     * @param terminalPolicy terminal policy
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
     * Returns an empty echo for diagnostic tests that do not care about a command.
     *
     * @return empty command echo
     */
    public static CommandEcho empty() {
        return EMPTY;
    }

    static CommandEcho from(LaunchPlan launchPlan) {
        List<String> environmentNames = launchPlan.environment().keySet().stream()
                .sorted(Comparator.naturalOrder())
                .toList();
        return new CommandEcho(
                launchPlan.command().getFirst(),
                launchPlan.command().size() - 1,
                launchPlan.workingDirectory(),
                environmentNames,
                launchPlan.outputMode(),
                launchPlan.terminalPolicy());
    }
}
