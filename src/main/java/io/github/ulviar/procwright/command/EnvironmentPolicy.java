/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.command;

/**
 * Controls how a child process environment is assembled.
 *
 * <p>In both modes, explicitly configured entries are applied last. Selecting {@link #CLEAN} does not remove those
 * overrides. Operating-system, shell, or PTY transport requirements may add their own variables; the system PTY
 * provider documents its additions in {@link io.github.ulviar.procwright.terminal.PtyProvider#system()}.
 */
public enum EnvironmentPolicy {
    /**
     * Starts from the current process environment and applies configured overrides.
     */
    INHERIT,

    /**
     * Starts from an empty environment and applies only configured overrides.
     */
    CLEAN
}
