/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

/** Internal lifecycle signal distinguishing a closed session stdin from delegate failures. */
@SuppressWarnings("serial")
final class SessionStdinClosedException extends IllegalStateException {

    SessionStdinClosedException() {
        super("Session stdin is closed");
    }
}
