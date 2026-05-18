package com.github.ulviar.icli.command;

/**
 * Signals that the process could not be started, supervised, or captured.
 */
public final class CommandExecutionException extends RuntimeException {

    /**
     * Creates an execution exception with a message and cause.
     *
     * @param message failure message
     * @param cause failure cause
     */
    public CommandExecutionException(String message, Throwable cause) {
        super(message, cause);
    }

    /**
     * Creates an execution exception with a message.
     *
     * @param message failure message
     */
    public CommandExecutionException(String message) {
        super(message);
    }
}
