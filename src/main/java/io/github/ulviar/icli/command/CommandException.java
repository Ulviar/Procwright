package io.github.ulviar.icli.command;

import java.util.Objects;

/**
 * Exception view of an unsuccessful command result.
 */
public final class CommandException extends RuntimeException {

    /** Command result that caused this exception. */
    private final CommandResult result;

    /**
     * Creates an exception for a command result.
     *
     * @param result command result
     */
    public CommandException(CommandResult result) {
        super(message(Objects.requireNonNull(result, "result")));
        this.result = result;
    }

    /**
     * Returns the command result that caused this exception.
     *
     * @return command result
     */
    public CommandResult result() {
        return result;
    }

    private static String message(CommandResult result) {
        if (result.timedOut()) {
            return "Command timed out";
        }
        return result.exitCode().stream()
                .mapToObj(exitCode -> "Command exited with code " + exitCode)
                .findFirst()
                .orElse("Command did not produce an exit code");
    }
}
