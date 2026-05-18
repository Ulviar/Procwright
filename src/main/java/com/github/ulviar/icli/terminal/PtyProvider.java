package com.github.ulviar.icli.terminal;

import com.github.ulviar.icli.command.CommandExecutionException;

/**
 * Service-provider interface for terminal-capable process transport.
 *
 * <p>The core library keeps PTY support behind this narrow boundary. Scenarios request a terminal through
 * {@link TerminalPolicy}; the runtime decides whether this provider is required, optional, or ignored.
 */
public interface PtyProvider {

    /**
     * Returns the best system PTY provider for the current platform.
     *
     * @return system PTY provider
     */
    static PtyProvider system() {
        return SystemPtyProvider.INSTANCE;
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
