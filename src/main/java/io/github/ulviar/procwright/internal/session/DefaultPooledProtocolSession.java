/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.DurationSupport;
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
    private final PooledRequestRunner<DefaultProtocolSession<I, O>> requestRunner;

    DefaultPooledProtocolSession(
            Supplier<ProtocolSession<I, O>> workerFactory, WorkerPoolSettings<ProtocolSession<I, O>> options) {
        this(
                workerFactory,
                options,
                session -> WorkerCloseSupport.closeOutcome(session, session.onExit(), session.physicalOutputCleanup()));
    }

    DefaultPooledProtocolSession(
            Supplier<ProtocolSession<I, O>> workerFactory,
            WorkerPoolSettings<ProtocolSession<I, O>> options,
            WorkerRetirement.Action<DefaultProtocolSession<I, O>> workerCloser) {
        Objects.requireNonNull(workerFactory, "workerFactory");
        this.options = Objects.requireNonNull(options, "options");
        Objects.requireNonNull(workerCloser, "workerCloser");
        this.pool = WorkerPoolController.fromSettings(
                () -> requireDefaultSession(workerFactory.get()),
                workerCloser,
                options,
                ProtocolPoolFailures.INSTANCE,
                "pooled protocol-session worker",
                "procwright-protocol-pool-replenish-",
                System::nanoTime);
        requestRunner = new PooledRequestRunner<>(pool, this::acquire, this::runReset, this::mapFailure);
    }

    @Override
    public O request(I request) {
        Objects.requireNonNull(request, "request");
        return requestObserved(request, null);
    }

    @Override
    public O request(I request, Duration timeout) {
        Objects.requireNonNull(request, "request");
        return requestObserved(request, DurationSupport.requirePositive(timeout, "timeout"));
    }

    private O requestObserved(I request, Duration timeout) {
        return requestRunner.run(
                session -> timeout == null ? session.request(request) : session.request(request, timeout));
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

    private static PooledProtocolSessionMetrics publicMetrics(PoolMetrics.Snapshot metrics) {
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

    @Override
    public void close() {
        PoolCloseSupport.await(pool::closeAsync, options.closeTimeout(), ProtocolPoolFailures.INSTANCE);
    }

    private WorkerPoolState.Lease<DefaultProtocolSession<I, O>> acquire() {
        return pool.acquire(this::isHealthy);
    }

    private WorkerPoolController.HealthOutcome isHealthy(
            DefaultProtocolSession<I, O> session, long acquireDeadlineNanos) {
        if (session.publicExitCompleted()) {
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
        if (session.publicExitCompleted()) {
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

    private PooledRequestRunner.Failure mapFailure(RuntimeException failure) {
        if (failure instanceof ProtocolSessionException exception) {
            return new PooledRequestRunner.Failure(retireReasonFor(exception), exception);
        }
        if (failure instanceof PooledProtocolSessionException exception) {
            return new PooledRequestRunner.Failure(retireReasonFor(exception), exception);
        }
        return new PooledRequestRunner.Failure(
                PooledWorkerRetireReason.WORKER_FAILED,
                new PooledProtocolSessionException(
                        PooledProtocolSessionException.Reason.WORKER_FAILED,
                        "Pooled protocol-session worker failed",
                        failure));
    }

    @SuppressWarnings("unchecked")
    private static <I, O> DefaultProtocolSession<I, O> requireDefaultSession(ProtocolSession<I, O> session) {
        Objects.requireNonNull(session, "workerFactory returned null");
        if (session instanceof DefaultProtocolSession<?, ?> defaultSession) {
            return (DefaultProtocolSession<I, O>) defaultSession;
        }
        throw new IllegalArgumentException("workerFactory must create a Procwright protocol session");
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
        public Throwable exposeAggregate(RuntimeException primary, Throwable aggregate) {
            if (primary instanceof PooledProtocolSessionException failure) {
                return new PooledProtocolSessionException(failure.reason(), failure.getMessage(), aggregate);
            }
            return aggregate;
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
