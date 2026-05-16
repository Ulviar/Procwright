package com.github.ulviar.icli;

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
        super("Command exited with code "
                + Objects.requireNonNull(result, "result").exitCode());
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
}
