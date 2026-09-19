/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Terminal process outcome for a raw, line, protocol, or Expect session.
 *
 * <p>A nonzero exit code is a normal result, not an exception by itself. An empty code means it was unavailable when
 * the outcome was selected. Completion describes the handle's logical outcome and does not prove that every descendant
 * or abandoned user callback has stopped; see the handle's onExit contract.
 *
 * <p>The timeout flag describes session lifecycle timeout, such as idle expiry. A terminal line or protocol request
 * timeout is instead exposed exceptionally through the corresponding handle.
 *
 * @param exitCode process exit code when available
 * @param timedOut whether session lifecycle timeout stopped the process
 */
public record SessionExit(OptionalInt exitCode, boolean timedOut) {

    /**
     * Creates a session exit state.
     *
     * @param exitCode process exit code when available
     * @param timedOut whether session lifecycle timeout stopped the process
     */
    public SessionExit {
        Objects.requireNonNull(exitCode, "exitCode");
    }
}
