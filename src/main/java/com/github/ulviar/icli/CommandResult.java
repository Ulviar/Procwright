package com.github.ulviar.icli;

import java.util.Objects;

/**
 * Completed one-shot command result.
 *
 * @param exitCode process exit code
 * @param stdout captured standard output
 * @param stderr captured standard error
 */
public record CommandResult(int exitCode, String stdout, String stderr) {

    /**
     * Creates a completed command result.
     */
    public CommandResult {
        Objects.requireNonNull(stdout, "stdout");
        Objects.requireNonNull(stderr, "stderr");
    }

    /**
     * Returns whether the command exited successfully.
     *
     * @return {@code true} when {@link #exitCode()} is zero
     */
    public boolean succeeded() {
        return exitCode == 0;
    }

    /**
     * Converts this result into an exception that preserves the result.
     *
     * @return exception for this command result
     */
    public CommandException toException() {
        return new CommandException(this);
    }
}
