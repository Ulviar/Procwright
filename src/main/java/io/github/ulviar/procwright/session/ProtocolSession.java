/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.internal.session.DefaultProtocolSession;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Generic request/response workflow over an interactive process.
 *
 * @param <I> request type
 * @param <O> response type
 */
public sealed interface ProtocolSession<I extends Object, O extends Object> extends AutoCloseable
        permits DefaultProtocolSession {

    /**
     * Sends one request and decodes one response with the default request timeout.
     *
     * <p>Only one request can be admitted to its adapter at a time. A timeout or interruption while waiting for that
     * serialized request slot occurs before adapter admission, writes no bytes for the waiting request, and leaves the
     * session open so the caller may retry. If a terminal or fatal session outcome was already selected, that outcome
     * takes precedence over the local wait failure.
     *
     * <p>After the serialized slot is acquired, the request owns the session. Timeout, interruption, failure to start or
     * complete an adapter callback, EOF, closed session, broken pipe, charset decode error, request/response size
     * overflow, output backlog overflow, protocol decoder failure, process exit, or another protocol failure then closes
     * the session because protocol state is no longer trustworthy. The thrown {@link ProtocolSessionException} contains
     * a stable reason, bounded transcript snapshot, and process exit code when known.
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
