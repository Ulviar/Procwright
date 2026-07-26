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
import io.github.ulviar.procwright.session.PooledSessionException;
import io.github.ulviar.procwright.session.PooledSessionMetrics;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * Pool of reusable line-oriented workers.
 *
 * <p>The pool reuses {@link LineSession} workers. It does not launch processes directly and does not expose worker
 * leases; returning a worker to the pool is owned by the pooled request lifecycle.
 */
public final class DefaultPooledLineSession implements PooledLineSession {

    private static final PooledSessionFailures POOL_FAILURES = new PooledSessionFailures("line");

    private final WorkerPoolSettings<LineSession> options;
    private final LineSessionSettings lineOptions;
    private final WorkerPoolController<DefaultLineSession> pool;
    private final PooledRequestRunner<DefaultLineSession> requestRunner;

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
            LongSupplier metricsClock) {
        this(
                workerFactory,
                lineOptions,
                options,
                metricsClock,
                session -> WorkerCloseSupport.closeOutcome(session, session.onExit()));
    }

    DefaultPooledLineSession(
            Supplier<LineSession> workerFactory,
            LineSessionSettings lineOptions,
            WorkerPoolSettings<LineSession> options,
            LongSupplier metricsClock,
            WorkerRetirement.Action<DefaultLineSession> workerCloser) {
        Objects.requireNonNull(workerFactory, "workerFactory");
        this.lineOptions = Objects.requireNonNull(lineOptions, "lineOptions");
        this.options = Objects.requireNonNull(options, "options");
        Objects.requireNonNull(workerCloser, "workerCloser");
        this.pool = WorkerPoolController.fromSettings(
                () -> requireDefaultSession(workerFactory.get()),
                workerCloser,
                options,
                POOL_FAILURES,
                "pooled line-session worker",
                "procwright-line-pool-worker-",
                metricsClock);
        requestRunner = new PooledRequestRunner<>(pool, this::acquire, this::runReset, this::mapFailure);
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
        return requestRunner.runPrepared(
                () -> encodeRequest(line, requestTimeout),
                (session, encodedRequest) ->
                        session.requestEncoded(encodedRequest.bytes(), encodedRequest.remainingTimeout()));
    }

    /**
     * Returns a current pool metrics snapshot.
     *
     * @return metrics snapshot
     */
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

    private WorkerPoolState.Lease<DefaultLineSession> acquire() {
        return pool.acquire(this::isHealthy);
    }

    private WorkerPoolController.HealthOutcome isHealthy(DefaultLineSession session, long acquireDeadlineNanos) {
        if (session.publicExitCompleted()) {
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
                () -> POOL_FAILURES.hookTimeout("Pooled line-session health check timed out"),
                exception -> POOL_FAILURES.interrupted(
                        "Interrupted while waiting for pooled line-session health check", exception),
                exception -> POOL_FAILURES.workerFailure("Pooled line-session health check failed", exception));
        if (session.publicExitCompleted()) {
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
                () -> POOL_FAILURES.hookTimeout("Pooled line-session reset hook timed out"),
                exception -> POOL_FAILURES.interrupted(
                        "Interrupted while waiting for pooled line-session reset hook", exception),
                exception -> POOL_FAILURES.workerFailure("Pooled line-session reset hook failed", exception));
    }

    static PooledWorkerRetireReason retireReasonFor(LineSessionException exception) {
        return switch (exception.reason()) {
            case TIMEOUT -> PooledWorkerRetireReason.TIMEOUT;
            case DECODE_ERROR, DECODER_FAILED -> PooledWorkerRetireReason.DECODER_FAILED;
            case EOF, PROCESS_EXITED -> PooledWorkerRetireReason.PROCESS_EXITED;
            default -> PooledWorkerRetireReason.WORKER_FAILED;
        };
    }

    private PooledRequestRunner.Failure mapFailure(RuntimeException failure) {
        if (failure instanceof LineSessionException exception) {
            return new PooledRequestRunner.Failure(retireReasonFor(exception), exception);
        }
        if (failure instanceof PooledSessionException exception) {
            return new PooledRequestRunner.Failure(PooledSessionFailures.retireReason(exception), exception);
        }
        return new PooledRequestRunner.Failure(
                PooledWorkerRetireReason.WORKER_FAILED,
                POOL_FAILURES.workerFailure("Pooled line-session worker failed", failure));
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

    private record EncodedRequest(byte[] bytes, Duration remainingTimeout) {

        private EncodedRequest {
            Objects.requireNonNull(bytes, "bytes");
            Objects.requireNonNull(remainingTimeout, "remainingTimeout");
        }
    }
}
