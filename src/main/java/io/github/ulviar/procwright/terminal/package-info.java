/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Terminal capability for session scenarios: PTY policy, providers, and terminal signals.
 *
 * <p>{@link io.github.ulviar.procwright.terminal.TerminalPolicy} decides whether a session runs on plain pipes
 * ({@code DISABLED}), uses a pseudo-terminal when one is available ({@code AUTO}), or fails fast when a terminal
 * cannot be provided ({@code REQUIRED}) — sessions never fall back silently.
 * {@link io.github.ulviar.procwright.terminal.PtyProvider} is the narrow SPI behind terminal transport;
 * {@link io.github.ulviar.procwright.terminal.TerminalSize} and
 * {@link io.github.ulviar.procwright.terminal.TerminalSignal} describe requested dimensions and control signals.
 *
 * <p>Terminal capability applies to session-family scenarios only; {@code run} and {@code listen} reject terminal
 * requests during resolution.
 */
@NullMarked
package io.github.ulviar.procwright.terminal;

import org.jspecify.annotations.NullMarked;
