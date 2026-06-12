/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.terminal;

/**
 * Describes terminal preference for a command scenario.
 */
public enum TerminalPolicy {
    /**
     * Runs with ordinary pipes and never requests a terminal.
     */
    DISABLED,

    /**
     * Lets a scenario choose a terminal when a terminal-capable transport exists.
     */
    AUTO,

    /**
     * Requires a terminal-capable transport and fails instead of silently falling back to pipes.
     */
    REQUIRED
}
