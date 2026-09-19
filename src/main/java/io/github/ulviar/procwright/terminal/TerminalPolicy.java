/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.terminal;

/**
 * Selects terminal transport for a session scenario before process launch.
 *
 * <p>A terminal may change buffering, echo, line endings, and output routing. Prefer ordinary pipes for machine
 * protocols unless the child requires a terminal. The policy does not turn a session into a terminal emulator.
 */
public enum TerminalPolicy {
    /**
     * Runs with ordinary pipes and never requests a terminal.
     */
    DISABLED,

    /**
     * Uses the configured PTY provider when it reports available; otherwise starts with ordinary pipes. If an available
     * provider fails during launch, that failure propagates instead of retrying the command with pipes.
     */
    AUTO,

    /**
     * Requires a terminal-capable transport. Opening fails with
     * {@link io.github.ulviar.procwright.command.CommandExecutionException} if the provider is unavailable or cannot
     * start the terminal process; it never falls back to pipes.
     */
    REQUIRED
}
