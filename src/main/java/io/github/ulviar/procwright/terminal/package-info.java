/* SPDX-License-Identifier: Apache-2.0 */

/**
 * Terminal capability for session scenarios: PTY policy, providers, and terminal signals.
 *
 * <p>{@link io.github.ulviar.procwright.terminal.TerminalPolicy} decides whether a session runs on plain pipes
 * ({@code DISABLED}), uses a pseudo-terminal when one is available ({@code AUTO}), or fails fast when a terminal
 * cannot be provided ({@code REQUIRED}). {@code AUTO} uses pipes when its provider is unavailable; a provider launch
 * failure propagates without retrying the command. {@code REQUIRED} never falls back to pipes.
 * {@link io.github.ulviar.procwright.terminal.PtyProvider} is the narrow SPI behind terminal transport;
 * {@link io.github.ulviar.procwright.terminal.TerminalSize} and
 * {@link io.github.ulviar.procwright.terminal.TerminalSignal} describe requested dimensions and control signals.
 *
 * <p>Terminal capability applies to session-family scenarios only; {@code run} and {@code listen} do not expose terminal
 * configuration. The built-in provider supports compatible Unix systems and their system helpers; see
 * {@link io.github.ulviar.procwright.terminal.PtyProvider#system()} for platform requirements and transport limits.
 * Terminal transport can change echo, buffering, line endings, and output routing; it is not terminal-screen emulation.
 *
 * <p>Unless explicitly marked {@code @Nullable}, reference parameters and return values are non-null; passing
 * {@code null} is unsupported. Collection elements and map entries are also non-null.
 */
@NullMarked
package io.github.ulviar.procwright.terminal;

import org.jspecify.annotations.NullMarked;
