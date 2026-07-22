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
 */
public interface PtyProvider {

    /**
     * Returns the best system PTY provider for the current platform.
     *
     * <p>Capability detection is bounded, cached, and verifies the exact absolute transport helpers before this provider
     * reports itself as available. The Unix provider fails closed when the executable token contains {@code =}, because
     * portable {@code env} operand syntax cannot represent that token unambiguously.
     *
     * @return system PTY provider
     */
    static PtyProvider system() {
        return SystemPtyProvider.instance();
    }

    /**
     * Returns an unavailable provider with a generic reason.
     *
     * @return unavailable provider
     */
    static PtyProvider unavailable() {
        return unavailable("no PTY provider is configured");
    }

    /**
     * Returns an unavailable provider with an explicit reason.
     *
     * @param reason unavailable reason
     * @return unavailable provider
     */
    static PtyProvider unavailable(String reason) {
        return new UnavailablePtyProvider(reason);
    }

    /**
     * Reports whether this provider can start PTY-backed processes in the current runtime.
     *
     * @return true when available
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
     * @param request resolved PTY request
     * @return started provider process
     * @throws CommandExecutionException when a terminal process cannot be started
     */
    Process start(PtyRequest request);
}
