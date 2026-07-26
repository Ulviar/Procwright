/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.PooledSessionException;
import io.github.ulviar.procwright.session.PooledSessionMetrics;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Pool of reusable typed protocol-session workers.
 *
 * @param <I> request type
 * @param <O> response type
 */
public final class DefaultPooledProtocolSession<I, O> implements PooledProtocolSession<I, O> {

    private static final PooledSessionFailures POOL_FAILURES = new PooledSessionFailures("protocol");

    private final WorkerPoolSettings<ProtocolSession<I, O>> options;
    private final WorkerPoolController<DefaultProtocolSession<I, O>> pool;
    private final PooledRequestRunner<DefaultProtocolSession<I, O>> requestRunner;

    DefaultPooledProtocolSession(
            Supplier<ProtocolSession<I, O>> workerFactory, WorkerPoolSettings<ProtocolSession<I, O>> options) {
        this(workerFactory, options, session -> WorkerCloseSupport.closeOutcome(session, session.onExit()));
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
                POOL_FAILURES,
                "pooled protocol-session worker",
                "procwright-protocol-pool-worker-",
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
    public PooledSessionMetrics metrics() {
        return pool.metrics();
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        return PoolCloseSupport.asyncView(pool.closeAsync(), POOL_FAILURES);
    }

    @Override
    public void close() {
        PoolCloseSupport.await(pool::closeAsync, options.closeTimeout(), POOL_FAILURES);
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
                () -> POOL_FAILURES.hookTimeout("Pooled protocol-session health check timed out"),
                exception -> POOL_FAILURES.interrupted(
                        "Interrupted while waiting for pooled protocol-session health check", exception),
                exception -> POOL_FAILURES.workerFailure("Pooled protocol-session health check failed", exception));
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
                () -> POOL_FAILURES.hookTimeout("Pooled protocol-session reset hook timed out"),
                exception -> POOL_FAILURES.interrupted(
                        "Interrupted while waiting for pooled protocol-session reset hook", exception),
                exception -> POOL_FAILURES.workerFailure("Pooled protocol-session reset hook failed", exception));
    }

    private static PooledWorkerRetireReason retireReasonFor(ProtocolSessionException exception) {
        return switch (exception.reason()) {
            case TIMEOUT -> PooledWorkerRetireReason.TIMEOUT;
            case DECODE_ERROR, PROTOCOL_DECODER_FAILED -> PooledWorkerRetireReason.DECODER_FAILED;
            case EOF, PROCESS_EXITED -> PooledWorkerRetireReason.PROCESS_EXITED;
            default -> PooledWorkerRetireReason.WORKER_FAILED;
        };
    }

    private PooledRequestRunner.Failure mapFailure(RuntimeException failure) {
        if (failure instanceof ProtocolSessionException exception) {
            return new PooledRequestRunner.Failure(retireReasonFor(exception), exception);
        }
        if (failure instanceof PooledSessionException exception) {
            return new PooledRequestRunner.Failure(PooledSessionFailures.retireReason(exception), exception);
        }
        return new PooledRequestRunner.Failure(
                PooledWorkerRetireReason.WORKER_FAILED,
                POOL_FAILURES.workerFailure("Pooled protocol-session worker failed", failure));
    }

    @SuppressWarnings("unchecked")
    private static <I, O> DefaultProtocolSession<I, O> requireDefaultSession(ProtocolSession<I, O> session) {
        Objects.requireNonNull(session, "workerFactory returned null");
        if (session instanceof DefaultProtocolSession<?, ?> defaultSession) {
            return (DefaultProtocolSession<I, O>) defaultSession;
        }
        throw new IllegalArgumentException("workerFactory must create a Procwright protocol session");
    }
}
