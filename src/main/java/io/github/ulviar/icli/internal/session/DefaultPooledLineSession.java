package io.github.ulviar.icli.internal.session;

import io.github.ulviar.icli.internal.DurationSupport;
import io.github.ulviar.icli.session.LineResponse;
import io.github.ulviar.icli.session.LineSession;
import io.github.ulviar.icli.session.LineSessionException;
import io.github.ulviar.icli.session.PooledLineSession;
import io.github.ulviar.icli.session.PooledLineSessionException;
import io.github.ulviar.icli.session.PooledLineSessionMetrics;
import io.github.ulviar.icli.session.PooledLineSessionOptions;
import io.github.ulviar.icli.session.PooledWorkerRetireReason;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Pool of reusable line-oriented workers.
 *
 * <p>The pool reuses {@link LineSession} workers. It does not launch processes directly and does not expose worker
 * leases; returning a worker to the pool is owned by the pooled request lifecycle.
 */
public final class DefaultPooledLineSession implements PooledLineSession {

    private final PooledLineSessionOptions options;
    private final WorkerPoolController<LineSession> pool;

    public DefaultPooledLineSession(Supplier<LineSession> workerFactory, PooledLineSessionOptions options) {
        Objects.requireNonNull(workerFactory, "workerFactory");
        this.options = Objects.requireNonNull(options, "options");
        this.pool = new WorkerPoolController<>(
                workerFactory,
                LineSession::close,
                new LinePoolOptions(options),
                LinePoolFailures.INSTANCE,
                "pooled line-session worker",
                "icli-line-pool-replenish-");
    }

    /**
     * Sends one pooled request using the worker line-session default timeout.
     *
     * @param line request line without the terminating line feed
     * @return decoded response
     */
    public LineResponse request(String line) {
        requireRequestLine(line);
        WorkerPoolController.Worker<LineSession> worker = null;
        boolean reusable = false;
        PooledWorkerRetireReason retireReason = PooledWorkerRetireReason.WORKER_FAILED;
        long requestStarted = 0;
        try {
            worker = acquire();
            requestStarted = System.nanoTime();
            LineResponse response = worker.session().request(line);
            worker.recordRequest();
            runReset(worker.session());
            pool.completedRequests(requestStarted);
            reusable = true;
            return response;
        } catch (LineSessionException exception) {
            retireReason = retireReasonFor(exception);
            pool.failedRequests(requestStarted);
            throw exception;
        } catch (PooledLineSessionException exception) {
            retireReason = retireReasonFor(exception);
            pool.failedRequests(requestStarted);
            throw exception;
        } catch (RuntimeException exception) {
            pool.failedRequests(requestStarted);
            throw new PooledLineSessionException(
                    PooledLineSessionException.Reason.WORKER_FAILED, "Pooled line-session worker failed", exception);
        } finally {
            if (worker != null) {
                pool.release(worker, reusable, retireReason);
            }
        }
    }

    /**
     * Sends one pooled request using an explicit request timeout.
     *
     * @param line request line without the terminating line feed
     * @param timeout request timeout
     * @return decoded response
     */
    public LineResponse request(String line, Duration timeout) {
        requireRequestLine(line);
        requirePositive(timeout, "timeout");
        WorkerPoolController.Worker<LineSession> worker = null;
        boolean reusable = false;
        PooledWorkerRetireReason retireReason = PooledWorkerRetireReason.WORKER_FAILED;
        long requestStarted = 0;
        try {
            worker = acquire();
            requestStarted = System.nanoTime();
            LineResponse response = worker.session().request(line, timeout);
            worker.recordRequest();
            runReset(worker.session());
            pool.completedRequests(requestStarted);
            reusable = true;
            return response;
        } catch (LineSessionException exception) {
            retireReason = retireReasonFor(exception);
            pool.failedRequests(requestStarted);
            throw exception;
        } catch (PooledLineSessionException exception) {
            retireReason = retireReasonFor(exception);
            pool.failedRequests(requestStarted);
            throw exception;
        } catch (RuntimeException exception) {
            pool.failedRequests(requestStarted);
            throw new PooledLineSessionException(
                    PooledLineSessionException.Reason.WORKER_FAILED, "Pooled line-session worker failed", exception);
        } finally {
            if (worker != null) {
                pool.release(worker, reusable, retireReason);
            }
        }
    }

    /**
     * Returns a current pool metrics snapshot.
     *
     * @return metrics snapshot
     */
    public PooledLineSessionMetrics metrics() {
        WorkerPoolController.MetricsSnapshot metrics = pool.metrics();
        return new PooledLineSessionMetrics(
                metrics.size(),
                metrics.idle(),
                metrics.leased(),
                metrics.starting(),
                metrics.retiring(),
                metrics.created(),
                metrics.retired(),
                metrics.completedRequests(),
                metrics.failedRequests(),
                metrics.failedStartups(),
                metrics.totalAcquireWaitNanos(),
                metrics.totalRequestDurationNanos(),
                metrics.totalWorkerStartupNanos(),
                metrics.retireReasons());
    }

    /**
     * Returns a future that completes once the pool is closed and all workers have exited the pool.
     *
     * @return drain future view
     */
    public CompletableFuture<Void> onDrained() {
        return pool.onDrained();
    }

    /**
     * Waits for the pool to drain after close.
     *
     * @param timeout maximum wait time
     * @return whether the pool drained before the timeout
     */
    public boolean awaitDrained(Duration timeout) {
        requirePositive(timeout, "timeout");
        try {
            pool.onDrained().get(Math.max(1, DurationSupport.saturatedMillis(timeout)), TimeUnit.MILLISECONDS);
            return true;
        } catch (java.util.concurrent.TimeoutException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } catch (java.util.concurrent.ExecutionException exception) {
            throw new PooledLineSessionException(
                    PooledLineSessionException.Reason.WORKER_FAILED, "Pool drain failed", exception.getCause());
        }
    }

    /**
     * Closes the pool. Idle workers are closed immediately; leased workers are closed when their current request
     * finishes.
     */
    @Override
    public void close() {
        pool.close();
    }

    private WorkerPoolController.Worker<LineSession> acquire() {
        return pool.acquire(this::isHealthy);
    }

    private boolean isHealthy(LineSession session, long acquireDeadlineNanos) {
        Duration timeout = WorkerHookSupport.boundedTimeout(options.hookTimeout(), acquireDeadlineNanos);
        if (timeout.isZero()) {
            throw new PooledLineSessionException(
                    PooledLineSessionException.Reason.ACQUIRE_TIMEOUT,
                    "Timed out waiting for pooled line-session health check");
        }
        return WorkerHookSupport.run(
                "icli-line-pool-health-",
                timeout,
                () -> options.healthCheck().test(session),
                () -> new PooledLineSessionException(
                        PooledLineSessionException.Reason.HOOK_TIMEOUT, "Pooled line-session health check timed out"),
                exception -> new PooledLineSessionException(
                        PooledLineSessionException.Reason.WORKER_FAILED,
                        "Pooled line-session health check failed",
                        exception));
    }

    private void runReset(LineSession session) {
        WorkerHookSupport.run(
                "icli-line-pool-reset-",
                options.hookTimeout(),
                () -> {
                    options.resetHook().accept(session);
                    return null;
                },
                () -> new PooledLineSessionException(
                        PooledLineSessionException.Reason.HOOK_TIMEOUT, "Pooled line-session reset hook timed out"),
                exception -> new PooledLineSessionException(
                        PooledLineSessionException.Reason.WORKER_FAILED,
                        "Pooled line-session reset hook failed",
                        exception));
    }

    private static PooledWorkerRetireReason retireReasonFor(LineSessionException exception) {
        return switch (exception.reason()) {
            case TIMEOUT -> PooledWorkerRetireReason.TIMEOUT;
            case DECODE_ERROR, DECODER_FAILED -> PooledWorkerRetireReason.DECODER_FAILED;
            case EOF -> PooledWorkerRetireReason.PROCESS_EXITED;
            default -> PooledWorkerRetireReason.WORKER_FAILED;
        };
    }

    private static PooledWorkerRetireReason retireReasonFor(PooledLineSessionException exception) {
        return switch (exception.reason()) {
            case HOOK_TIMEOUT -> PooledWorkerRetireReason.TIMEOUT;
            default -> PooledWorkerRetireReason.WORKER_FAILED;
        };
    }

    private static String requireRequestLine(String line) {
        Objects.requireNonNull(line, "line");
        if (line.indexOf('\n') >= 0 || line.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("line must not contain line separators");
        }
        return line;
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private record LinePoolOptions(PooledLineSessionOptions options) implements WorkerPoolController.PoolOptions {

        private LinePoolOptions {
            Objects.requireNonNull(options, "options");
        }

        @Override
        public int maxSize() {
            return options.maxSize();
        }

        @Override
        public int warmupSize() {
            return options.warmupSize();
        }

        @Override
        public int minIdle() {
            return options.minIdle();
        }

        @Override
        public Duration acquireTimeout() {
            return options.acquireTimeout();
        }

        @Override
        public int maxRequestsPerWorker() {
            return options.maxRequestsPerWorker();
        }

        @Override
        public Duration maxWorkerAge() {
            return options.maxWorkerAge();
        }

        @Override
        public boolean backgroundReplenishment() {
            return options.backgroundReplenishment();
        }
    }

    private enum LinePoolFailures implements WorkerPoolController.FailureFactory {
        INSTANCE;

        @Override
        public RuntimeException closed(String message) {
            return new PooledLineSessionException(
                    PooledLineSessionException.Reason.CLOSED, "Pooled line session is closed");
        }

        @Override
        public RuntimeException acquireTimeout(String message) {
            return new PooledLineSessionException(PooledLineSessionException.Reason.ACQUIRE_TIMEOUT, message);
        }

        @Override
        public RuntimeException acquireInterrupted(String message, InterruptedException cause) {
            return new PooledLineSessionException(PooledLineSessionException.Reason.ACQUIRE_TIMEOUT, message, cause);
        }

        @Override
        public RuntimeException startupFailed(String message, Throwable cause) {
            return new PooledLineSessionException(PooledLineSessionException.Reason.STARTUP_FAILED, message, cause);
        }
    }
}
