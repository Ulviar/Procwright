package io.github.ulviar.icli;

import io.github.ulviar.icli.command.CommandSpec;
import io.github.ulviar.icli.command.RunOptions;
import java.util.Objects;

/**
 * Static entry point for scenario-first command workflows.
 */
public final class Icli {

    private Icli() {}

    /**
     * Creates a command service for an executable.
     *
     * @param executable executable name or path
     * @return command service
     */
    public static CommandService command(String executable) {
        return CommandService.forCommand(executable);
    }

    /**
     * Creates a command service from an explicit command specification.
     *
     * @param commandSpec command specification
     * @return command service
     */
    public static CommandService command(CommandSpec commandSpec) {
        return new CommandService(Objects.requireNonNull(commandSpec, "commandSpec"), RunOptions.defaults());
    }

    /**
     * Creates a command service for a command line interpreted by the system shell.
     *
     * <p>Prefer {@link #command(String)} plus argv arguments for untrusted values unless shell syntax is required.
     *
     * @param commandLine command line interpreted by the system shell
     * @return command service
     */
    public static CommandService shellCommand(String commandLine) {
        return CommandService.forShellCommand(commandLine);
    }
}
