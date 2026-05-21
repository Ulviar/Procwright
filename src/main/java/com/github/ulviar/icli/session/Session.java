package com.github.ulviar.icli.session;

import com.github.ulviar.icli.command.CommandInput;
import com.github.ulviar.icli.internal.session.DefaultSession;
import com.github.ulviar.icli.terminal.TerminalSignal;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.CompletableFuture;

/**
 * Raw handle for an interactive command process.
 *
 * <p>A session exposes process streams directly and owns process lifecycle coordination. It does not serialize
 * line-oriented request/response workflows; higher-level scenarios should build those guarantees on top of this raw
 * handle.
 *
 * <p>This sealed interface is an iCLI-owned handle contract, not a service-provider interface. Applications receive
 * session instances from {@code CommandService}; custom implementations are not supported because higher-level helpers
 * rely on iCLI's internal output-ownership invariant.
 */
public sealed interface Session extends AutoCloseable permits DefaultSession {

    /**
     * Returns raw process stdout.
     *
     * <p>Calling this method claims stdout ownership for raw caller code. Higher-level helpers such as {@link Expect}
     * also need exclusive output ownership; mixing raw stream reads with those helpers can make later operations fail
     * with {@link IllegalStateException} or lose protocol state.
     *
     * @return stdout stream
     */
    InputStream stdout();

    /**
     * Returns raw process stderr.
     *
     * <p>Calling this method claims stderr ownership for raw caller code. Do not read this stream concurrently with an
     * iCLI scenario/helper that drains stderr for diagnostics.
     *
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
     * Writes a terminal control signal and flushes stdin.
     *
     * @param signal terminal signal
     */
    void sendSignal(TerminalSignal signal);

    /**
     * Closes process stdin. The session may keep running until the process exits or is closed.
     */
    void closeStdin();

    /**
     * Returns a process exit future view.
     *
     * @return process exit future
     */
    CompletableFuture<SessionExit> onExit();

    /**
     * Creates an expect automation helper using default options.
     *
     * @return expect helper
     */
    default Expect expect() {
        return expect(ExpectOptions.defaults());
    }

    /**
     * Creates an expect automation helper using explicit options.
     *
     * @param options expect options
     * @return expect helper
     * @throws IllegalArgumentException when this session was not created by iCLI
     */
    default Expect expect(ExpectOptions options) {
        return Expect.on(this, options);
    }

    /**
     * Stops the process through the configured shutdown policy. Calling this method more than once has no effect.
     */
    @Override
    void close();
}
