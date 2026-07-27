/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.command.CommandInput;
import io.github.ulviar.procwright.internal.session.DefaultSession;
import io.github.ulviar.procwright.terminal.TerminalSignal;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;

/**
 * Raw handle for an interactive command process.
 *
 * <p>A session exposes process streams directly and owns process lifecycle coordination. Choose {@code lineSession()},
 * {@code protocolSession()}, {@code listen()}, or {@code interactive().expect()} before launch when Procwright should
 * consume output instead.
 *
 * <p>This sealed interface is a Procwright-owned handle contract, not a service-provider interface. Applications receive
 * session instances from {@code CommandService}; custom implementations are not supported.
 */
public sealed interface Session extends AutoCloseable permits DefaultSession {

    /**
     * Returns raw process stdout.
     * @return stdout stream
     */
    InputStream stdout();

    /**
     * Returns raw process stderr.
     * @return stderr stream
     */
    InputStream stderr();

    /**
     * Returns raw process stdin guarded by the session lifecycle state.
     *
     * @return stdin stream
     */
    OutputStream stdin();

    /**
     * Writes text using the session charset and flushes stdin.
     *
     * @param text text to write
     */
    void send(String text);

    /**
     * Writes a line feed terminated text line using the session charset and flushes stdin.
     *
     * @param line line text without the terminating line feed
     */
    void sendLine(String line);

    /**
     * Writes explicit command input bytes and flushes stdin.
     *
     * @param input input bytes
     */
    void send(CommandInput input);

    /**
     * Writes the control byte represented by a terminal signal and flushes stdin.
     *
     * <p>With a PTY, the terminal driver may interpret the byte and deliver an operating-system signal to the process.
     * With ordinary pipes, this method only writes the byte to stdin; it does not signal the process.
     *
     * @param signal terminal signal
     */
    void sendSignal(TerminalSignal signal);

    /**
     * Logically closes process stdin for further writes without stopping the process.
     *
     * <p>After close work is admitted and started, the method returns without waiting for physical stream close or EOF
     * delivery; a concurrent write may hold the stream monitor indefinitely. If close work cannot be admitted or started,
     * the failure path performs bounded terminal cleanup before this method throws. If an admitted close fails later
     * while the session is still running, {@link #onExit()} completes exceptionally with the original failure. Calling
     * this method more than once has no effect.
     */
    void closeStdin();

    /**
     * Returns an isolated view of the session's terminal future.
     *
     * <p>Completion confirms the selected terminal process outcome. On natural exit, raw stdout and stderr remain owned
     * by the caller until their streams or this session are explicitly closed. Potentially blocking physical stream
     * closes continue independently. A late stdin-close failure can complete a still-running session exceptionally as
     * described by {@link #closeStdin()}; it cannot change a public outcome that has already been selected. Cancelling
     * the returned view cannot cancel the session.
     *
     * @return process exit future
     */
    CompletableFuture<SessionExit> onExit();

    /**
     * Requests process shutdown through the configured policy.
     *
     * <p>If another terminal action already owns shutdown, this method returns without joining its cleanup; use
     * {@link #onExit()} to await the logical terminal outcome. Calling this method more than once has no effect.
     * Potentially blocking physical stream closes run asynchronously.
     */
    @Override
    void close();
}
