package io.github.ulviar.icli;

/**
 * Base class for iCLI-produced runtime failures.
 *
 * <p>This type is a common catch point for command execution, session, pooling, streaming, expect, and integration
 * failures. It does not replace scenario-specific exceptions: callers should catch a more specific exception when they
 * need structured data such as a failure reason, transcript, diagnostics, or command result.
 */
public abstract class IcliException extends RuntimeException {

    /**
     * Creates an iCLI exception with a message.
     *
     * @param message failure message
     */
    protected IcliException(String message) {
        super(message);
    }

    /**
     * Creates an iCLI exception with a message and cause.
     *
     * @param message failure message
     * @param cause failure cause
     */
    protected IcliException(String message, Throwable cause) {
        super(message, cause);
    }
}
