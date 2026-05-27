package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.internal.session.DefaultPooledProtocolSession;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Pool of reusable typed protocol-session workers.
 *
 * @param <I> request type
 * @param <O> response type
 */
public sealed interface PooledProtocolSession<I, O> extends AutoCloseable permits DefaultPooledProtocolSession {

    /**
     * Sends one pooled request using the worker protocol-session default timeout.
     *
     * @param request request value
     * @return decoded response
     */
    O request(I request);

    /**
     * Sends one pooled request using an explicit request timeout.
     *
     * @param request request value
     * @param timeout request timeout
     * @return decoded response
     */
    O request(I request, Duration timeout);

    /**
     * Returns a current pool metrics snapshot.
     *
     * @return metrics snapshot
     */
    PooledProtocolSessionMetrics metrics();

    /**
     * Returns a future that completes once the pool is closed and all workers have exited the pool.
     *
     * @return drain future view
     */
    CompletableFuture<Void> onDrained();

    /**
     * Waits for the pool to drain after close.
     *
     * @param timeout maximum wait time
     * @return whether the pool drained before the timeout
     */
    boolean awaitDrained(Duration timeout);

    /**
     * Closes the pool.
     */
    @Override
    void close();
}
