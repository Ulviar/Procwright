/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.build.release;

final class WorkflowValidationException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    WorkflowValidationException(String message) {
        super(message);
    }

    WorkflowValidationException(String message, Throwable cause) {
        super(message, cause);
    }
}
