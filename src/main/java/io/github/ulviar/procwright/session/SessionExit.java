/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import java.util.Objects;
import java.util.OptionalInt;

/**
 * Exit state of an interactive session process.
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
