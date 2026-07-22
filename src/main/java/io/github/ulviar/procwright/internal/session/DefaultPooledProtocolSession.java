/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.PooledProtocolSessionException;
import io.github.ulviar.procwright.session.PooledProtocolSessionMetrics;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Pool of reusable typed protocol-session workers.
 *
 * @param <I> request type
 * @param <O> response type
 */
public final class DefaultPooledProtocolSession<I, O> implements PooledProtocolSession<I, O> {

    private final WorkerPoolSettings<ProtocolSession<I, O>> options;
    private final WorkerPoolController<DefaultProtocolSession<I, O>> pool;

    DefaultPooledProtocolSession(
            Supplier<ProtocolSession<I, O>> workerFactory, WorkerPoolSettings<ProtocolSession<I, O>> options) {
        this(
                workerFactory,
                options,
                PoolRetirementDispatcher::execute,
                (session, admission) -> WorkerCloseSupport.initiateCloseAndObserve(
                        session, session.onExit(), session.physicalOutputCleanup(), admission));
    }

    DefaultPooledProtocolSession(
            Supplier<ProtocolSession<I, O>> workerFactory,
            WorkerPoolSettings<ProtocolSession<I, O>> options,
            WorkerPoolController.TerminalRetirementDispatcher terminalDispatcher) {
        this(
                workerFactory,
                options,
                terminalDispatcher,
                (session, admission) -> WorkerCloseSupport.initiateCloseAndObserve(
                        session, session.onExit(), session.physicalOutputCleanup(), admission, terminalDispatcher));
    }

    DefaultPooledProtocolSession(
            Supplier<ProtocolSession<I, O>> workerFactory,
            WorkerPoolSettings<ProtocolSession<I, O>> options,
            WorkerPoolController.TerminalRetirementDispatcher terminalDispatcher,
            WorkerPoolController.WorkerCloseAction<DefaultProtocolSession<I, O>> workerCloser) {
        Objects.requireNonNull(workerFactory, "workerFactory");
        this.options = Objects.requireNonNull(options, "options");
        Objects.requireNonNull(terminalDispatcher, "terminalDispatcher");
        Objects.requireNonNull(workerCloser, "workerCloser");
        this.pool = new WorkerPoolController<>(
                () -> requireDefaultSession(workerFactory.get()),
                workerCloser,
                new ProtocolPoolOptions(options),
                ProtocolPoolFailures.INSTANCE,
                "pooled protocol-session worker",
                "procwright-protocol-pool-replenish-",
                System::nanoTime,
                terminalDispatcher);
    }

    @Override
    public O request(I request) {
        Objects.requireNonNull(request, "request");
        return requestObserved(request, null);
    }

    @Override
    public O request(I request, Duration timeout) {
        Objects.requireNonNull(request, "request");
        return requestObserved(request, requirePositive(timeout, "timeout"));
    }

    private O requestObserved(I request, Duration timeout) {
        WorkerPoolController<DefaultProtocolSession<I, O>>.RequestObservation observation = pool.observeRequest();
        observation.pauseForAcquire();
        WorkerPoolController.Worker<DefaultProtocolSession<I, O>> worker = null;
        boolean reusable = false;
        PooledWorkerRetireReason retireReason = PooledWorkerRetireReason.WORKER_FAILED;
        try {
            worker = acquire();
            observation.resumeAfterAcquire();
            O response = timeout == null
                    ? worker.session().request(request)
                    : worker.session().request(request, timeout);
            worker.recordRequest();
            if (pool.retirementReasonFor(worker) == null) {
                try {
                    runReset(worker.session());
                } catch (RuntimeException resetFailure) {
                    retireReason = PooledWorkerRetireReason.RESET_FAILED;
                    observation.succeed();
                    return response;
                } catch (Error resetError) {
                    retireReason = PooledWorkerRetireReason.RESET_FAILED;
                    observation.succeed();
                    throw resetError;
                }
            }
            observation.succeed();
            reusable = true;
            return response;
        } catch (ProtocolSessionException exception) {
            retireReason = retireReasonFor(exception);
            observation.fail();
            throw exception;
        } catch (PooledProtocolSessionException exception) {
            retireReason = retireReasonFor(exception);
            observation.fail();
            throw exception;
        } catch (RuntimeException exception) {
            observation.fail();
            throw new PooledProtocolSessionException(
                    PooledProtocolSessionException.Reason.WORKER_FAILED,
                    "Pooled protocol-session worker failed",
                    exception);
        } catch (Error error) {
            observation.fail();
            throw error;
        } finally {
            if (worker != null) {
                pool.release(worker, reusable, retireReason);
            }
        }
    }

    @Override
    public PooledProtocolSessionMetrics metrics() {
        return publicMetrics(pool.metrics());
    }

    boolean awaitMetrics(Predicate<PooledProtocolSessionMetrics> condition, Duration timeout)
            throws InterruptedException {
        Objects.requireNonNull(condition, "condition");
        return pool.awaitMetrics(metrics -> condition.test(publicMetrics(metrics)), timeout);
    }

    private static PooledProtocolSessionMetrics publicMetrics(WorkerPoolController.MetricsSnapshot metrics) {
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
                metrics.failedWorkerCloses(),
                metrics.totalAcquireWaitNanos(),
                metrics.totalRequestDurationNanos(),
                metrics.totalWorkerStartupNanos(),
                metrics.retireReasons());
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        return PoolCloseSupport.asyncView(pool.closeAsync(), ProtocolPoolFailures.INSTANCE);
    }

    CompletableFuture<Void> slotReleaseCompletion() {
        return pool.slotReleaseCompletion();
    }

    @Override
    public void close() {
        PoolCloseSupport.await(pool.closeAsync(), options.closeTimeout(), ProtocolPoolFailures.INSTANCE);
    }

    private WorkerPoolController.Worker<DefaultProtocolSession<I, O>> acquire() {
        return pool.acquire(this::isHealthy);
    }

    private WorkerPoolController.HealthOutcome isHealthy(
            DefaultProtocolSession<I, O> session, long acquireDeadlineNanos) {
        if (session.exitCompleted()) {
            return WorkerPoolController.HealthOutcome.PROCESS_EXITED;
        }
        Duration timeout = WorkerHookSupport.boundedTimeout(options.hookTimeout(), acquireDeadlineNanos);
        if (timeout.isZero()) {
            return WorkerPoolController.HealthOutcome.ACQUIRE_TIMEOUT;
        }
        boolean accepted = WorkerHookSupport.run(
                "procwright-protocol-pool-health-",
                timeout,
                () -> options.healthCheck().test(session),
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
        if (session.exitCompleted()) {
            return WorkerPoolController.HealthOutcome.PROCESS_EXITED;
        }
        return accepted ? WorkerPoolController.HealthOutcome.HEALTHY : WorkerPoolController.HealthOutcome.HEALTH_FAILED;
    }

    private void runReset(DefaultProtocolSession<I, O> session) {
        WorkerHookSupport.run(
                "procwright-protocol-pool-reset-",
                options.hookTimeout(),
                () -> {
                    options.resetHook().accept(session);
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

    @SuppressWarnings("unchecked")
    private static <I, O> DefaultProtocolSession<I, O> requireDefaultSession(ProtocolSession<I, O> session) {
        Objects.requireNonNull(session, "workerFactory returned null");
        if (session instanceof DefaultProtocolSession<?, ?> defaultSession) {
            return (DefaultProtocolSession<I, O>) defaultSession;
        }
        throw new IllegalArgumentException("workerFactory must create a Procwright protocol session");
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private record ProtocolPoolOptions(WorkerPoolSettings<?> options) implements WorkerPoolController.PoolOptions {

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
        public Duration closeTimeout() {
            return options.closeTimeout();
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

    private enum ProtocolPoolFailures implements WorkerPoolController.FailureFactory, PoolCloseSupport.FailureFactory {
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

        @Override
        public RuntimeException retirementFailed(String message, Throwable cause) {
            return new PooledProtocolSessionException(
                    PooledProtocolSessionException.Reason.WORKER_FAILED, message, cause);
        }

        @Override
        public RuntimeException drainTimeout(Duration timeout) {
            return new PooledProtocolSessionException(
                    PooledProtocolSessionException.Reason.DRAIN_TIMEOUT,
                    "Pooled protocol session did not drain within " + timeout);
        }

        @Override
        public RuntimeException interrupted(InterruptedException cause) {
            return new PooledProtocolSessionException(
                    PooledProtocolSessionException.Reason.INTERRUPTED,
                    "Interrupted while closing pooled protocol session",
                    cause);
        }

        @Override
        public RuntimeException workerFailed(Throwable cause) {
            return new PooledProtocolSessionException(
                    PooledProtocolSessionException.Reason.WORKER_FAILED,
                    "Pooled protocol-session worker cleanup failed",
                    cause);
        }
    }
}
