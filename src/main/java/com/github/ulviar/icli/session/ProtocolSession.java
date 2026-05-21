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
     * <p>Only one request can be active at a time. The adapter owns request writing and response decoding. On timeout,
     * EOF, closed session, broken pipe, charset decode error, request/response size overflow, output backlog overflow,
     * protocol decoder failure, process exit, or another protocol failure, the session is closed because the protocol
     * state is no longer trustworthy. The thrown {@link ProtocolSessionException} contains a stable reason, bounded
     * transcript snapshot, and process exit code when known.
     *
     * @param request request value
     * @return decoded response
     * @throws ProtocolSessionException when the request cannot be completed safely
     */
    O request(I request);

    /**
     * Sends one request and decodes one response with an explicit timeout.
     *
     * <p>Failure handling is the same as {@link #request(Object)}.
     *
     * @param request request value
     * @param timeout request timeout
     * @return decoded response
     * @throws ProtocolSessionException when the request cannot be completed safely
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
