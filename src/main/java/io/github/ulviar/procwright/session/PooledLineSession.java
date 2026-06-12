/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.session;

import io.github.ulviar.procwright.internal.session.DefaultPooledLineSession;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Pool of reusable line-oriented workers.
 *
 * <p>The pool reuses {@link LineSession} workers. It does not launch processes directly and does not expose worker
 * leases; returning a worker to the pool is owned by the pooled request lifecycle.
 *
 * <p>This sealed interface is a Procwright-owned handle contract, not a service-provider interface. Applications receive
 * pooled line sessions from {@code CommandService}.
 */
public sealed interface PooledLineSession extends AutoCloseable permits DefaultPooledLineSession {

    /**
     * Sends one pooled request using the worker line-session default timeout.
     *
     * @param line request line without the terminating line feed
     * @return decoded response
     */
    LineResponse request(String line);

    /**
     * Sends one pooled request using an explicit request timeout.
     *
     * @param line request line without the terminating line feed
     * @param timeout request timeout
     * @return decoded response
     */
    LineResponse request(String line, Duration timeout);

    /**
     * Returns a current pool metrics snapshot.
     *
     * @return metrics snapshot
     */
    PooledLineSessionMetrics metrics();

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
     * Closes the pool. Idle workers are closed immediately; leased workers are closed when their current request
     * finishes.
     */
    @Override
    void close();
}
