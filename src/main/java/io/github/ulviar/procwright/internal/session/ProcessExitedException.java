/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

/**
 * Signals that a session write was rejected because the process already exited.
 *
 * <p>The raw {@link DefaultSession} stdin contract reports rejected writes as {@link IllegalStateException}; this
 * subtype lets line and protocol sessions classify the rejection as a typed process-exit failure instead of a generic
 * closed-session failure.
 */
@SuppressWarnings("serial")
final class ProcessExitedException extends IllegalStateException {

    ProcessExitedException(String message) {
        super(message);
    }
}
