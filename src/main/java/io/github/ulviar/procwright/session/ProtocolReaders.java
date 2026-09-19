/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

/**
 * Protocol response readers for stdout and stderr.
 *
 * <p>Both streams are pumped independently, so an adapter can read stdout without draining stderr itself. The readers
 * share the byte and character budgets of the current response. An unread stderr backlog does not fail a stdout-only
 * exchange, but a later attempt to read overflowing stderr fails with
 * {@link ProtocolSessionException.Reason#RESPONSE_TOO_LARGE}.
 *
 * <p>The returned readers have the callback scope and thread confinement documented by {@link ProtocolReader}.
 */
public interface ProtocolReaders {

    /**
     * Returns the stdout reader.
     *
     * @return stdout reader
     */
    ProtocolReader stdout();

    /**
     * Returns the stderr reader.
     *
     * @return stderr reader
     */
    ProtocolReader stderr();
}
