package com.github.ulviar.icli.session;

import com.github.ulviar.icli.internal.session.DefaultProtocolSession;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Generic request/response workflow over an interactive process.
 *
 * @param <I> request type
 * @param <O> response type
 */
public sealed interface ProtocolSession<I, O> extends AutoCloseable permits DefaultProtocolSession {

    /**
     * Sends one request and decodes one response with the default request timeout.
     *
     * @param request request value
     * @return decoded response
     */
    O request(I request);

    /**
     * Sends one request and decodes one response with an explicit timeout.
     *
     * @param request request value
     * @param timeout request timeout
     * @return decoded response
     */
    O request(I request, Duration timeout);

    /**
     * Returns the current bounded transcript snapshot.
     *
     * @return transcript snapshot
     */
    ProtocolTranscript transcript();

    /**
     * Returns the underlying process exit future view.
     *
     * @return process exit future
     */
    CompletableFuture<SessionExit> onExit();

    /**
     * Closes the underlying interactive session.
     */
    @Override
    void close();
}
