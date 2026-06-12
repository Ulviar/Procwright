/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.integration;

import io.github.ulviar.procwright.ProcwrightException;

/**
 * Signals invalid JSON text.
 */
public final class JsonParseException extends ProcwrightException {

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
