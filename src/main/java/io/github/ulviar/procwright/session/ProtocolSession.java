/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.internal.session.DefaultProtocolSession;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Typed request/response workflow over a process that accepts repeated requests while remaining alive.
 *
 * <p>The session owns process input and output; its {@link ProtocolAdapter} defines request framing and response
 * boundaries. Concurrent callers are supported and each exchange is serialized; order across calling threads is not
 * specified. Close the handle with try-with-resources when its work is complete.
 *
 * @see io.github.ulviar.procwright.CommandService#protocolSession(java.util.function.Supplier)
 *
 * @param <I> request type
 * @param <O> response type
 */
public sealed interface ProtocolSession<I extends Object, O extends Object> extends AutoCloseable
        permits DefaultProtocolSession {

    /**
     * Sends one request and decodes one response with the configured request timeout, initially five seconds.
     *
     * <p>The timeout covers the serialized wait and both adapter callbacks. Cleanup after a terminal timeout uses the
     * separate shutdown policy and can extend the time before this call returns.
     *
     * <p>Only one request can be admitted to its adapter at a time. A timeout or interruption while waiting for that
     * serialized request slot occurs before adapter admission, writes no bytes for the waiting request, and leaves the
     * session open so the caller may retry. If a terminal or fatal session outcome was already selected, that outcome
     * takes precedence over the local wait failure.
     *
     * <p>After the serialized slot is acquired, the request owns the session. Timeout, interruption, failure to start or
     * complete an adapter callback, EOF, closed session, broken pipe, charset decode error, oversized request, response
     * or pending output, protocol decoder failure, process exit, or another protocol failure then closes the session
     * because protocol state is no longer trustworthy. The thrown {@link ProtocolSessionException} contains
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
     * @param timeout positive timeout for this entire exchange, including its serialized wait
     * @return decoded response
     * @throws IllegalArgumentException if the timeout is zero or negative
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
     * <p>The future completes after the process has a terminal outcome and protocol output has either drained naturally
     * or been logically abandoned during shutdown. After a request timeout, an adapter callback that ignores
     * interruption or an output read blocked in the JDK may still be running; neither delays this future, and a late
     * result cannot replace the selected outcome. The future does not wait for a potentially blocking physical close of
     * the process streams. A terminal protocol-session failure accepted before the public outcome is selected completes
     * it exceptionally.
     *
     * <p>Each call returns an independent view. Cancelling or completing it does not affect the process or other views.
     * Keep synchronous completion actions short; use asynchronous continuations for blocking work.
     *
     * @return cancellation-isolated process exit future
     */
    CompletableFuture<SessionExit> onExit();

    /**
     * Stops accepting requests and closes the process through its configured shutdown policy.
     *
     * <p>Calling this method more than once has no effect. A concurrent terminal action may already own cleanup;
     * use {@link #onExit()} to await the logical outcome. Physical stream closes can continue after that outcome.
     */
    @Override
    void close();
}
