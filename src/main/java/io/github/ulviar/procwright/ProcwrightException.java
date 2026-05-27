package io.github.ulviar.procwright;

/**
 * Base class for Procwright-produced runtime failures.
 *
 * <p>This type is a common catch point for command execution, session, pooling, streaming, expect, and integration
 * failures. It does not replace scenario-specific exceptions: callers should catch a more specific exception when they
 * need structured data such as a failure reason, transcript, diagnostics, or command result.
 */
public abstract class ProcwrightException extends RuntimeException {

    /**
     * Creates a Procwright exception with a message.
     *
     * @param message failure message
     */
    protected ProcwrightException(String message) {
        super(message);
    }

    /**
     * Creates a Procwright exception with a message and cause.
     *
     * @param message failure message
     * @param cause failure cause
     */
    protected ProcwrightException(String message, Throwable cause) {
        super(message, cause);
    }
}
