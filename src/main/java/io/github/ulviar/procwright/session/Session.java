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
 * <p>The caller must consume stdout and stderr concurrently while the process runs. Procwright does not drain raw
 * output; an unread pipe can fill and block the child, including while the caller is writing stdin or waiting for
 * {@link #onExit()}. Close the session, normally with try-with-resources, even after natural process exit.
 *
 * <p>Raw I/O is blocking and has no per-call timeout. This handle does not make a sequence of writes and reads into an
 * atomic request/response exchange; coordinate access in the application or use {@link LineSession} or
 * {@link ProtocolSession}. The configured idle timeout observes successful stream reads and writes, not application
 * work between them.
 *
 * <p>This sealed interface is a Procwright-owned handle contract, not a service-provider interface. Applications receive
 * session instances from {@code CommandService}; custom implementations are not supported.
 */
public sealed interface Session extends AutoCloseable permits DefaultSession {

    /**
     * Returns caller-owned process stdout; consume it concurrently with {@link #stderr()}.
     * @return stdout stream
     */
    InputStream stdout();

    /**
     * Returns caller-owned process stderr; consume it concurrently with {@link #stdout()}.
     * @return stderr stream
     */
    InputStream stderr();

    /**
     * Returns process stdin guarded by the session lifecycle state.
     *
     * <p>Writes do not flush automatically. Call {@link OutputStream#flush()} after a complete request. Closing this
     * stream has the same logical-close semantics as {@link #closeStdin()}.
     *
     * @return stdin stream
     */
    OutputStream stdin();

    /**
     * Writes text using the session charset and flushes stdin.
     *
     * @param text text to write without an added separator
     * @throws IllegalStateException if stdin is closed or the process has exited
     * @throws io.github.ulviar.procwright.command.CommandExecutionException if writing or flushing fails
     */
    void send(String text);

    /**
     * Writes a line feed terminated text line using the session charset and flushes stdin.
     *
     * <p>Existing line separators are preserved; this method appends one LF even when the text already ends with one.
     *
     * @param line text to write before the added line feed
     * @throws IllegalStateException if stdin is closed or the process has exited
     * @throws io.github.ulviar.procwright.command.CommandExecutionException if writing or flushing fails
     */
    void sendLine(String line);

    /**
     * Writes explicit command input bytes and flushes stdin.
     *
     * @param input input bytes, written without an added separator
     * @throws IllegalStateException if stdin is closed or the process has exited
     * @throws io.github.ulviar.procwright.command.CommandExecutionException if writing or flushing fails
     */
    void send(CommandInput input);

    /**
     * Writes the control byte represented by a terminal signal and flushes stdin.
     *
     * <p>With a PTY, the terminal driver may interpret the byte and deliver an operating-system signal to the process.
     * With ordinary pipes, this method only writes the byte to stdin; it does not signal the process.
     *
     * @param signal terminal signal
     * @throws IllegalStateException if stdin is closed or the process has exited
     * @throws io.github.ulviar.procwright.command.CommandExecutionException if writing or flushing fails
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
     * or completing the returned view cannot cancel the session or change its outcome. Each call returns an independent
     * view. Keep synchronous completion actions short; use asynchronous continuations for blocking work.
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
