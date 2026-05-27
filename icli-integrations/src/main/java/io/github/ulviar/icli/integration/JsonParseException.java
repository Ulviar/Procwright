package io.github.ulviar.icli.integration;

import io.github.ulviar.icli.IcliException;

/**
 * Signals invalid JSON text.
 */
public final class JsonParseException extends IcliException {

    /**
     * Creates a parse exception.
     *
     * @param message error message
     */
    public JsonParseException(String message) {
        super(message);
    }

    /**
     * Creates a parse exception with a cause.
     *
     * @param message error message
     * @param cause error cause
     */
    public JsonParseException(String message, Throwable cause) {
        super(message, cause);
    }
}
