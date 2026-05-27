package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.PooledProtocolSessionException;
import io.github.ulviar.procwright.session.PooledProtocolSessionInvocation;
import io.github.ulviar.procwright.session.PooledProtocolSessionMetrics;
import io.github.ulviar.procwright.session.PooledProtocolSessionOptions;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * Pool of reusable typed protocol-session workers.
 *
 * @param <I> request type
 * @param <O> response type
 */
public final class DefaultPooledProtocolSession<I, O> implements PooledProtocolSession<I, O> {

    private final PooledProtocolSessionInvocation<I, O> invocation;
    private final PooledProtocolSessionOptions options;
    private final WorkerPoolController<ProtocolSession<I, O>> pool;

    public DefaultPooledProtocolSession(
            Supplier<ProtocolSession<I, O>> workerFactory, PooledProtocolSessionInvocation<I, O> invocation) {
        Objects.requireNonNull(workerFactory, "workerFactory");
        this.invocation = Objects.requireNonNull(invocation, "invocation");
        this.options = invocation.options();
        this.pool = new WorkerPoolController<>(
                workerFactory,
                ProtocolSession::close,
                new ProtocolPoolOptions(options),
                ProtocolPoolFailures.INSTANCE,
                "pooled protocol-session worker",
                "procwright-protocol-pool-replenish-");
    }

    @Override
    public O request(I request) {
        WorkerPoolController.Worker<ProtocolSession<I, O>> worker = null;
        boolean reusable = false;
        PooledWorkerRetireReason retireReason = PooledWorkerRetireReason.WORKER_FAILED;
        long requestStarted = 0;
        try {
            worker = acquire();
            requestStarted = System.nanoTime();
            O response = worker.session().request(request);
            worker.recordRequest();
            runReset(worker.session());
            pool.completedRequests(requestStarted);
            reusable = true;
            return response;
        } catch (ProtocolSessionException exception) {
            retireReason = retireReasonFor(exception);
            pool.failedRequests(requestStarted);
            throw exception;
        } catch (PooledProtocolSessionException exception) {
            retireReason = retireReasonFor(exception);
            pool.failedRequests(requestStarted);
            throw exception;
        } catch (RuntimeException exception) {
            pool.failedRequests(requestStarted);
            throw new PooledProtocolSessionException(
                    PooledProtocolSessionException.Reason.WORKER_FAILED,
                    "Pooled protocol-session worker failed",
                    exception);
        } finally {
            if (worker != null) {
                pool.release(worker, reusable, retireReason);
            }
        }
    }

    @Override
    public O request(I request, Duration timeout) {
        requirePositive(timeout, "timeout");
        WorkerPoolController.Worker<ProtocolSession<I, O>> worker = null;
        boolean reusable = false;
        PooledWorkerRetireReason retireReason = PooledWorkerRetireReason.WORKER_FAILED;
        long requestStarted = 0;
        try {
            worker = acquire();
            requestStarted = System.nanoTime();
            O response = worker.session().request(request, timeout);
            worker.recordRequest();
            runReset(worker.session());
            pool.completedRequests(requestStarted);
            reusable = true;
            return response;
        } catch (ProtocolSessionException exception) {
            retireReason = retireReasonFor(exception);
            pool.failedRequests(requestStarted);
            throw exception;
        } catch (PooledProtocolSessionException exception) {
            retireReason = retireReasonFor(exception);
            pool.failedRequests(requestStarted);
            throw exception;
        } catch (RuntimeException exception) {
            pool.failedRequests(requestStarted);
            throw new PooledProtocolSessionException(
                    PooledProtocolSessionException.Reason.WORKER_FAILED,
                    "Pooled protocol-session worker failed",
                    exception);
        } finally {
            if (worker != null) {
                pool.release(worker, reusable, retireReason);
            }
        }
    }

    @Override
    public PooledProtocolSessionMetrics metrics() {
        WorkerPoolController.MetricsSnapshot metrics = pool.metrics();
        return new PooledProtocolSessionMetrics(
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

    @Override
    public CompletableFuture<Void> onDrained() {
        return pool.onDrained();
    }

    @Override
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
            throw new PooledProtocolSessionException(
                    PooledProtocolSessionException.Reason.WORKER_FAILED, "Pool drain failed", exception.getCause());
        }
    }

    @Override
    public void close() {
        pool.close();
    }

    private WorkerPoolController.Worker<ProtocolSession<I, O>> acquire() {
        return pool.acquire(this::isHealthy);
    }

    private boolean isHealthy(ProtocolSession<I, O> session, long acquireDeadlineNanos) {
        Duration timeout = WorkerHookSupport.boundedTimeout(options.hookTimeout(), acquireDeadlineNanos);
        if (timeout.isZero()) {
            throw new PooledProtocolSessionException(
                    PooledProtocolSessionException.Reason.ACQUIRE_TIMEOUT,
                    "Timed out waiting for pooled protocol-session health check");
        }
        return WorkerHookSupport.run(
                "procwright-protocol-pool-health-",
                timeout,
                () -> invocation.healthCheck().test(session),
                () -> new PooledProtocolSessionException(
                        PooledProtocolSessionException.Reason.HOOK_TIMEOUT,
                        "Pooled protocol-session health check timed out"),
                exception -> new PooledProtocolSessionException(
                        PooledProtocolSessionException.Reason.INTERRUPTED,
                        "Interrupted while waiting for pooled protocol-session health check",
                        exception),
                exception -> new PooledProtocolSessionException(
                        PooledProtocolSessionException.Reason.WORKER_FAILED,
                        "Pooled protocol-session health check failed",
                        exception));
    }

    private void runReset(ProtocolSession<I, O> session) {
        WorkerHookSupport.run(
                "procwright-protocol-pool-reset-",
                options.hookTimeout(),
                () -> {
                    invocation.resetHook().accept(session);
                    return null;
                },
                () -> new PooledProtocolSessionException(
                        PooledProtocolSessionException.Reason.HOOK_TIMEOUT,
                        "Pooled protocol-session reset hook timed out"),
                exception -> new PooledProtocolSessionException(
                        PooledProtocolSessionException.Reason.INTERRUPTED,
                        "Interrupted while waiting for pooled protocol-session reset hook",
                        exception),
                exception -> new PooledProtocolSessionException(
                        PooledProtocolSessionException.Reason.WORKER_FAILED,
                        "Pooled protocol-session reset hook failed",
                        exception));
    }

    private static PooledWorkerRetireReason retireReasonFor(ProtocolSessionException exception) {
        return switch (exception.reason()) {
            case TIMEOUT -> PooledWorkerRetireReason.TIMEOUT;
            case DECODE_ERROR, PROTOCOL_DECODER_FAILED -> PooledWorkerRetireReason.DECODER_FAILED;
            case EOF, PROCESS_EXITED -> PooledWorkerRetireReason.PROCESS_EXITED;
            default -> PooledWorkerRetireReason.WORKER_FAILED;
        };
    }

    private static PooledWorkerRetireReason retireReasonFor(PooledProtocolSessionException exception) {
        return switch (exception.reason()) {
            case HOOK_TIMEOUT -> PooledWorkerRetireReason.TIMEOUT;
            default -> PooledWorkerRetireReason.WORKER_FAILED;
        };
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private record ProtocolPoolOptions(PooledProtocolSessionOptions options)
            implements WorkerPoolController.PoolOptions {

        private ProtocolPoolOptions {
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

    private enum ProtocolPoolFailures implements WorkerPoolController.FailureFactory {
        INSTANCE;

        @Override
        public RuntimeException closed(String message) {
            return new PooledProtocolSessionException(
                    PooledProtocolSessionException.Reason.CLOSED, "Pooled protocol session is closed");
        }

        @Override
        public RuntimeException acquireTimeout(String message) {
            return new PooledProtocolSessionException(PooledProtocolSessionException.Reason.ACQUIRE_TIMEOUT, message);
        }

        @Override
        public RuntimeException acquireInterrupted(String message, InterruptedException cause) {
            return new PooledProtocolSessionException(
                    PooledProtocolSessionException.Reason.INTERRUPTED, message, cause);
        }

        @Override
        public RuntimeException startupFailed(String message, Throwable cause) {
            return new PooledProtocolSessionException(
                    PooledProtocolSessionException.Reason.STARTUP_FAILED, message, cause);
        }
    }
}
