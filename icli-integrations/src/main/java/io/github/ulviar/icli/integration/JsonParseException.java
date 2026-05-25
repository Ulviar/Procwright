package io.github.ulviar.icli.integration;

/**
 * Signals invalid JSON text.
 */
public final class JsonParseException extends IllegalArgumentException {

    /**
     * Creates a parse exception.
     *
     * @param message error message
     */
    public JsonParseException(String message) {
        super(message);
    }
}
