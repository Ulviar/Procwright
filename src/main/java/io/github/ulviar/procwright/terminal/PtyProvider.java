/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.terminal;

import io.github.ulviar.procwright.command.CommandExecutionException;

/**
 * Service-provider interface for terminal-capable process transport.
 *
 * <p>The core library keeps PTY support behind this narrow boundary. Scenarios request a terminal through
 * {@link TerminalPolicy}; the runtime decides whether this provider is required, optional, or ignored.
 *
 * <p>Session Drafts retain the supplied provider instance. Reusing a Draft for concurrent terminal-enabled opens, or
 * opening a line or protocol pool with multiple workers, can call {@link #available()}, {@link #description()}, and
 * {@link #start(PtyRequest)} concurrently on that instance. A retained provider must be thread-safe; otherwise, use
 * separate Draft branches with separate provider instances.
 *
 * <p>Providers are trusted extensions. The runtime does not wrap individual methods of the returned {@link Process}
 * and {@link ProcessHandle} in timeout or thread isolation. Provider metadata and process signal operations must
 * return promptly; timed waits must honor their timeout. A blocking custom implementation can delay session operations
 * and cleanup beyond their configured deadlines. Descendant scans and asynchronous process-destroy fallback retain
 * their own bounds. The system provider bounds its own capability detection and startup.
 */
public interface PtyProvider {

    /**
     * Returns the best system PTY provider for the current platform.
     *
     * <p>The built-in provider supports compatible Unix systems, including macOS and Linux. It requires working
     * {@code script}, {@code stty}, {@code env}, and {@code dd} helpers in standard {@code /usr/bin} or {@code /bin}
     * locations and {@code /bin/sh}. Windows ConPTY is not implemented. Use {@link #available()} and
     * {@link #description()} to check the detected capability.
     *
     * <p>Capability detection is bounded, cached, and verifies the exact absolute transport helpers before this provider
     * reports itself as available. Availability is not a guarantee that every later launch will succeed. The returned
     * provider is shared and supports concurrent use.
     *
     * <p>The child receives the requested environment policy and overrides. The provider adds {@code TERM=xterm-256color}
     * only when {@code TERM} is absent and sets {@code COLUMNS} and {@code LINES} from the requested terminal dimensions.
     * Target environment values are supplied only to the final child, not to the transport wrapper.
     *
     * <p>System-provider launch limits are 256 command tokens including the executable, 256 final environment entries,
     * 32 KiB per encoded token/name/value, and 128 KiB total encoded payload. Text must be representable in the native
     * process charset. The executable token must not contain {@code =}, because portable {@code env} operand syntax
     * cannot represent it unambiguously. Exceeding these limits fails launch with {@link CommandExecutionException}.
     * These transport limits do not apply to ordinary pipe-based process execution.
     *
     * @return system PTY provider
     */
    static PtyProvider system() {
        return SystemPtyProvider.instance();
    }

    /**
     * Returns an unavailable provider with a generic reason.
     *
     * <p>{@link TerminalPolicy#AUTO} can use pipes with this provider; {@link TerminalPolicy#REQUIRED} fails to open.
     *
     * @return unavailable provider
     */
    static PtyProvider unavailable() {
        return unavailable("no PTY provider is configured");
    }

    /**
     * Returns an unavailable provider with an explicit reason.
     *
     * @param reason non-blank unavailable reason without NUL characters
     * @return unavailable provider
     * @throws IllegalArgumentException if {@code reason} is blank or contains NUL
     */
    static PtyProvider unavailable(String reason) {
        return new UnavailablePtyProvider(reason);
    }

    /**
     * Reports whether this provider can start PTY-backed processes in the current runtime.
     *
     * <p>This is a capability check, not a reservation or a promise that the next launch will succeed.
     *
     * @return {@code true} when terminal capability is available
     */
    boolean available();

    /**
     * Returns a human-readable provider description or unavailable reason.
     *
     * @return provider description
     */
    String description();

    /**
     * Starts the requested command inside a terminal.
     *
     * <p>On successful return, process and stream ownership passes to the calling runtime, which reads output, writes
     * input, observes exit, and performs shutdown. The returned {@link Process} must support those ordinary JDK
     * operations. A provider is responsible for cleaning up processes it started if it throws before returning them.
     *
     * <p>A terminal can alter echo, line endings, buffering, and stdout/stderr routing. Provider implementations should
     * document their transport behavior. The system provider exposes the child's terminal output through process
     * stdout; process stderr remains available for transport diagnostics rather than a separate child stderr stream.
     *
     * @param request resolved PTY request
     * @return started provider process
     * @throws CommandExecutionException when a terminal process cannot be started
     */
    Process start(PtyRequest request);
}
