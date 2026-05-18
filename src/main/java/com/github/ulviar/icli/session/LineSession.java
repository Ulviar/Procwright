package com.github.ulviar.icli.session;

import com.github.ulviar.icli.internal.session.DefaultLineSession;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Line-oriented request/response workflow over an interactive process.
 *
 * <p>Only one request is decoded at a time. Custom response decoders consume stdout lines through a deadline-aware
 * reader, while stderr is drained into the bounded transcript for diagnostics.
 *
 * <p>This sealed interface is an iCLI-owned handle contract, not a service-provider interface. Applications receive line
 * sessions from {@code CommandService}.
 */
public sealed interface LineSession extends AutoCloseable permits DefaultLineSession {

    /**
     * Sends one line and decodes one response with the default request timeout.
     *
     * @param line request line without the terminating line feed
     * @return decoded response
     */
    LineResponse request(String line);

    /**
     * Sends one line and decodes one response with an explicit request timeout.
     *
     * @param line request line without the terminating line feed
     * @param timeout request timeout
     * @return decoded response
     */
    LineResponse request(String line, Duration timeout);

    /**
     * Returns the current bounded transcript snapshot.
     *
     * @return transcript snapshot
     */
    LineTranscript transcript();

    /**
     * Returns the underlying process exit future view.
     *
     * @return process exit future
     */
    CompletableFuture<SessionExit> onExit();

    /**
     * Closes the underlying interactive session. Calling this method more than once has no effect.
     */
    @Override
    void close();
}
