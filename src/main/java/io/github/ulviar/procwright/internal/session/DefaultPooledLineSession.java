/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.LineTranscript;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledLineSessionException;
import io.github.ulviar.procwright.session.PooledLineSessionMetrics;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Pool of reusable line-oriented workers.
 *
 * <p>The pool reuses {@link LineSession} workers. It does not launch processes directly and does not expose worker
 * leases; returning a worker to the pool is owned by the pooled request lifecycle.
 */
public final class DefaultPooledLineSession implements PooledLineSession {

    private final WorkerPoolSettings<LineSession> options;
    private final LineSessionSettings lineOptions;
    private final WorkerPoolController<DefaultLineSession> pool;

    public DefaultPooledLineSession(
            Supplier<LineSession> workerFactory,
            LineSessionSettings lineOptions,
            WorkerPoolSettings<LineSession> options) {
        this(workerFactory, lineOptions, options, System::nanoTime);
    }

    DefaultPooledLineSession(
            Supplier<LineSession> workerFactory,
            LineSessionSettings lineOptions,
            WorkerPoolSettings<LineSession> options,
            WorkerPoolController.NanoClock metricsClock) {
        this(
                workerFactory,
                lineOptions,
                options,
                metricsClock,
                PoolRetirementDispatcher::execute,
                (session, admission) -> WorkerCloseSupport.initiateCloseAndObserve(
                        session, session.onExit(), session.physicalOutputCleanup(), admission));
    }

    DefaultPooledLineSession(
            Supplier<LineSession> workerFactory,
            LineSessionSettings lineOptions,
            WorkerPoolSettings<LineSession> options,
            WorkerPoolController.NanoClock metricsClock,
            WorkerPoolController.TerminalRetirementDispatcher terminalDispatcher) {
        this(
                workerFactory,
                lineOptions,
                options,
                metricsClock,
                terminalDispatcher,
                (session, admission) -> WorkerCloseSupport.initiateCloseAndObserve(
                        session, session.onExit(), session.physicalOutputCleanup(), admission, terminalDispatcher));
    }

    DefaultPooledLineSession(
            Supplier<LineSession> workerFactory,
            LineSessionSettings lineOptions,
            WorkerPoolSettings<LineSession> options,
            WorkerPoolController.NanoClock metricsClock,
            WorkerPoolController.TerminalRetirementDispatcher terminalDispatcher,
            WorkerPoolController.WorkerCloseAction<DefaultLineSession> workerCloser) {
        Objects.requireNonNull(workerFactory, "workerFactory");
        this.lineOptions = Objects.requireNonNull(lineOptions, "lineOptions");
        this.options = Objects.requireNonNull(options, "options");
        Objects.requireNonNull(terminalDispatcher, "terminalDispatcher");
        Objects.requireNonNull(workerCloser, "workerCloser");
        this.pool = new WorkerPoolController<>(
                () -> requireDefaultSession(workerFactory.get()),
                workerCloser,
                new LinePoolOptions(options),
                LinePoolFailures.INSTANCE,
                "pooled line-session worker",
                "procwright-line-pool-replenish-",
                metricsClock,
                terminalDispatcher);
    }

    /**
     * Sends one pooled request using the worker line-session default timeout.
     *
     * @param line request line without the terminating line feed
     * @return decoded response
     */
    public LineResponse request(String line) {
        LineRequestEncoder.validate(line);
        return requestObserved(line, lineOptions.requestTimeout());
    }

    /**
     * Sends one pooled request using an explicit request timeout.
     *
     * @param line request line without the terminating line feed
     * @param timeout request timeout
     * @return decoded response
     */
    public LineResponse request(String line, Duration timeout) {
        LineRequestEncoder.validate(line);
        return requestObserved(line, DurationSupport.requirePositive(timeout, "timeout"));
    }

    private LineResponse requestObserved(String line, Duration requestTimeout) {
        WorkerPoolController<DefaultLineSession>.RequestObservation observation = pool.observeRequest();
        WorkerPoolController.Worker<DefaultLineSession> worker = null;
        boolean reusable = false;
        PooledWorkerRetireReason retireReason = PooledWorkerRetireReason.WORKER_FAILED;
        try {
            EncodedRequest encodedRequest = encodeRequest(line, requestTimeout);
            observation.pauseForAcquire();
            worker = acquire();
            observation.resumeAfterAcquire();
            LineResponse response =
                    worker.session().requestEncoded(encodedRequest.bytes(), encodedRequest.remainingTimeout());
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
        } catch (LineSessionException exception) {
            retireReason = retireReasonFor(exception);
            observation.fail();
            throw exception;
        } catch (PooledLineSessionException exception) {
            retireReason = retireReasonFor(exception);
            observation.fail();
            throw exception;
        } catch (RuntimeException exception) {
            observation.fail();
            throw new PooledLineSessionException(
                    PooledLineSessionException.Reason.WORKER_FAILED, "Pooled line-session worker failed", exception);
        } catch (Error error) {
            observation.fail();
            throw error;
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
        return publicMetrics(pool.metrics());
    }

    boolean awaitMetrics(Predicate<PooledLineSessionMetrics> condition, Duration timeout) throws InterruptedException {
        Objects.requireNonNull(condition, "condition");
        return pool.awaitMetrics(metrics -> condition.test(publicMetrics(metrics)), timeout);
    }

    private static PooledLineSessionMetrics publicMetrics(WorkerPoolController.MetricsSnapshot metrics) {
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
                metrics.failedWorkerCloses(),
                metrics.totalAcquireWaitNanos(),
                metrics.totalRequestDurationNanos(),
                metrics.totalWorkerStartupNanos(),
                metrics.retireReasons());
    }

    @Override
    public CompletableFuture<Void> closeAsync() {
        return PoolCloseSupport.asyncView(pool.closeAsync(), LinePoolFailures.INSTANCE);
    }

    CompletableFuture<Void> slotReleaseCompletion() {
        return pool.slotReleaseCompletion();
    }

    @Override
    public void close() {
        PoolCloseSupport.await(pool.closeAsync(), options.closeTimeout(), LinePoolFailures.INSTANCE);
    }

    private WorkerPoolController.Worker<DefaultLineSession> acquire() {
        return pool.acquire(this::isHealthy);
    }

    private WorkerPoolController.HealthOutcome isHealthy(DefaultLineSession session, long acquireDeadlineNanos) {
        if (session.exitCompleted()) {
            return WorkerPoolController.HealthOutcome.PROCESS_EXITED;
        }
        Duration timeout = WorkerHookSupport.boundedTimeout(options.hookTimeout(), acquireDeadlineNanos);
        if (timeout.isZero()) {
            return WorkerPoolController.HealthOutcome.ACQUIRE_TIMEOUT;
        }
        boolean accepted = WorkerHookSupport.run(
                "procwright-line-pool-health-",
                timeout,
                () -> options.healthCheck().test(session),
                () -> new PooledLineSessionException(
                        PooledLineSessionException.Reason.HOOK_TIMEOUT, "Pooled line-session health check timed out"),
                exception -> new PooledLineSessionException(
                        PooledLineSessionException.Reason.INTERRUPTED,
                        "Interrupted while waiting for pooled line-session health check",
                        exception),
                exception -> new PooledLineSessionException(
                        PooledLineSessionException.Reason.WORKER_FAILED,
                        "Pooled line-session health check failed",
                        exception));
        if (session.exitCompleted()) {
            return WorkerPoolController.HealthOutcome.PROCESS_EXITED;
        }
        return accepted ? WorkerPoolController.HealthOutcome.HEALTHY : WorkerPoolController.HealthOutcome.HEALTH_FAILED;
    }

    private void runReset(LineSession session) {
        WorkerHookSupport.run(
                "procwright-line-pool-reset-",
                options.hookTimeout(),
                () -> {
                    options.resetHook().accept(session);
                    return null;
                },
                () -> new PooledLineSessionException(
                        PooledLineSessionException.Reason.HOOK_TIMEOUT, "Pooled line-session reset hook timed out"),
                exception -> new PooledLineSessionException(
                        PooledLineSessionException.Reason.INTERRUPTED,
                        "Interrupted while waiting for pooled line-session reset hook",
                        exception),
                exception -> new PooledLineSessionException(
                        PooledLineSessionException.Reason.WORKER_FAILED,
                        "Pooled line-session reset hook failed",
                        exception));
    }

    static PooledWorkerRetireReason retireReasonFor(LineSessionException exception) {
        return switch (exception.reason()) {
            case TIMEOUT -> PooledWorkerRetireReason.TIMEOUT;
            case DECODE_ERROR, DECODER_FAILED -> PooledWorkerRetireReason.DECODER_FAILED;
            case EOF, PROCESS_EXITED -> PooledWorkerRetireReason.PROCESS_EXITED;
            default -> PooledWorkerRetireReason.WORKER_FAILED;
        };
    }

    private static PooledWorkerRetireReason retireReasonFor(PooledLineSessionException exception) {
        return switch (exception.reason()) {
            case HOOK_TIMEOUT -> PooledWorkerRetireReason.TIMEOUT;
            default -> PooledWorkerRetireReason.WORKER_FAILED;
        };
    }

    private EncodedRequest encodeRequest(String line, Duration timeout) {
        long deadlineNanos = DurationSupport.deadlineFromNow(timeout);
        byte[] bytes = LineRequestEncoder.encodeUntil(
                line,
                lineOptions,
                message -> new LineSessionException(
                        LineSessionException.Reason.REQUEST_TOO_LARGE, new LineTranscript("", false, false), message),
                () -> requestFailure(LineSessionException.Reason.TIMEOUT, "Line request timed out", null),
                exception -> requestFailure(
                        LineSessionException.Reason.FAILURE, "Interrupted while encoding line request", exception),
                deadlineNanos);
        long remainingNanos = deadlineNanos - System.nanoTime();
        if (remainingNanos <= 0) {
            throw requestFailure(LineSessionException.Reason.TIMEOUT, "Line request timed out", null);
        }
        return new EncodedRequest(bytes, Duration.ofNanos(remainingNanos));
    }

    private static LineSessionException requestFailure(
            LineSessionException.Reason reason, String message, Throwable cause) {
        if (cause == null) {
            return new LineSessionException(reason, new LineTranscript("", false, false), message);
        }
        return new LineSessionException(reason, new LineTranscript("", false, false), message, cause);
    }

    private static DefaultLineSession requireDefaultSession(LineSession session) {
        Objects.requireNonNull(session, "workerFactory returned null");
        if (session instanceof DefaultLineSession defaultSession) {
            return defaultSession;
        }
        throw new IllegalArgumentException("workerFactory must create a Procwright line session");
    }

    private record LinePoolOptions(WorkerPoolSettings<LineSession> options)
            implements WorkerPoolController.PoolOptions {

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

    private record EncodedRequest(byte[] bytes, Duration remainingTimeout) {

        private EncodedRequest {
            Objects.requireNonNull(bytes, "bytes");
            Objects.requireNonNull(remainingTimeout, "remainingTimeout");
        }
    }

    private enum LinePoolFailures implements WorkerPoolController.FailureFactory, PoolCloseSupport.FailureFactory {
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
            return new PooledLineSessionException(PooledLineSessionException.Reason.INTERRUPTED, message, cause);
        }

        @Override
        public RuntimeException startupFailed(String message, Throwable cause) {
            return new PooledLineSessionException(PooledLineSessionException.Reason.STARTUP_FAILED, message, cause);
        }

        @Override
        public RuntimeException retirementFailed(String message, Throwable cause) {
            return new PooledLineSessionException(PooledLineSessionException.Reason.WORKER_FAILED, message, cause);
        }

        @Override
        public RuntimeException drainTimeout(Duration timeout) {
            return new PooledLineSessionException(
                    PooledLineSessionException.Reason.DRAIN_TIMEOUT,
                    "Pooled line session did not drain within " + timeout);
        }

        @Override
        public RuntimeException interrupted(InterruptedException cause) {
            return new PooledLineSessionException(
                    PooledLineSessionException.Reason.INTERRUPTED,
                    "Interrupted while closing pooled line session",
                    cause);
        }

        @Override
        public RuntimeException workerFailed(Throwable cause) {
            return new PooledLineSessionException(
                    PooledLineSessionException.Reason.WORKER_FAILED,
                    "Pooled line-session worker cleanup failed",
                    cause);
        }
    }
}
