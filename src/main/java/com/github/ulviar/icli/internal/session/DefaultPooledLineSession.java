package com.github.ulviar.icli.internal.session;

import com.github.ulviar.icli.internal.DurationSupport;
import com.github.ulviar.icli.internal.Threading;
import com.github.ulviar.icli.session.LineResponse;
import com.github.ulviar.icli.session.LineSession;
import com.github.ulviar.icli.session.LineSessionException;
import com.github.ulviar.icli.session.PooledLineSession;
import com.github.ulviar.icli.session.PooledLineSessionException;
import com.github.ulviar.icli.session.PooledLineSessionMetrics;
import com.github.ulviar.icli.session.PooledLineSessionOptions;
import com.github.ulviar.icli.session.PooledWorkerRetireReason;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;
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

    private final Supplier<LineSession> workerFactory;
    private final PooledLineSessionOptions options;
    private final ArrayDeque<Worker> idle = new ArrayDeque<>();
    private final EnumMap<PooledWorkerRetireReason, Long> retireReasons = new EnumMap<>(PooledWorkerRetireReason.class);
    private final Object lock = new Object();
    private final CompletableFuture<Void> drained = new CompletableFuture<>();

    private boolean closing;
    private int size;
    private int leased;
    private int starting;
    private int retiring;
    private long created;
    private long retired;
    private long completedRequests;
    private long failedRequests;
    private long failedStartups;
    private long totalAcquireWaitNanos;
    private long totalRequestDurationNanos;
    private long totalWorkerStartupNanos;

    public DefaultPooledLineSession(Supplier<LineSession> workerFactory, PooledLineSessionOptions options) {
        this.workerFactory = Objects.requireNonNull(workerFactory, "workerFactory");
        this.options = Objects.requireNonNull(options, "options");
        warmup();
    }

    /**
     * Sends one pooled request using the worker line-session default timeout.
     *
     * @param line request line without the terminating line feed
     * @return decoded response
     */
    public LineResponse request(String line) {
        requireRequestLine(line);
        Worker worker = null;
        boolean reusable = false;
        PooledWorkerRetireReason retireReason = PooledWorkerRetireReason.WORKER_FAILED;
        long requestStarted = 0;
        try {
            worker = acquire();
            requestStarted = System.nanoTime();
            LineResponse response = worker.session.request(line);
            worker.requests++;
            runReset(worker);
            completedRequests(requestStarted);
            reusable = true;
            return response;
        } catch (LineSessionException exception) {
            retireReason = retireReasonFor(exception);
            failedRequests(requestStarted);
            throw exception;
        } catch (PooledLineSessionException exception) {
            retireReason = retireReasonFor(exception);
            failedRequests(requestStarted);
            throw exception;
        } catch (RuntimeException exception) {
            failedRequests(requestStarted);
            throw new PooledLineSessionException(
                    PooledLineSessionException.Reason.WORKER_FAILED, "Pooled line-session worker failed", exception);
        } finally {
            if (worker != null) {
                release(worker, reusable, retireReason);
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
        Worker worker = null;
        boolean reusable = false;
        PooledWorkerRetireReason retireReason = PooledWorkerRetireReason.WORKER_FAILED;
        long requestStarted = 0;
        try {
            worker = acquire();
            requestStarted = System.nanoTime();
            LineResponse response = worker.session.request(line, timeout);
            worker.requests++;
            runReset(worker);
            completedRequests(requestStarted);
            reusable = true;
            return response;
        } catch (LineSessionException exception) {
            retireReason = retireReasonFor(exception);
            failedRequests(requestStarted);
            throw exception;
        } catch (PooledLineSessionException exception) {
            retireReason = retireReasonFor(exception);
            failedRequests(requestStarted);
            throw exception;
        } catch (RuntimeException exception) {
            failedRequests(requestStarted);
            throw new PooledLineSessionException(
                    PooledLineSessionException.Reason.WORKER_FAILED, "Pooled line-session worker failed", exception);
        } finally {
            if (worker != null) {
                release(worker, reusable, retireReason);
            }
        }
    }

    /**
     * Returns a current pool metrics snapshot.
     *
     * @return metrics snapshot
     */
    public PooledLineSessionMetrics metrics() {
        synchronized (lock) {
            return new PooledLineSessionMetrics(
                    size,
                    idle.size(),
                    leased,
                    starting,
                    retiring,
                    created,
                    retired,
                    completedRequests,
                    failedRequests,
                    failedStartups,
                    totalAcquireWaitNanos,
                    totalRequestDurationNanos,
                    totalWorkerStartupNanos,
                    Map.copyOf(retireReasons));
        }
    }

    /**
     * Returns a future that completes once the pool is closed and all workers have exited the pool.
     *
     * @return drain future view
     */
    public CompletableFuture<Void> onDrained() {
        return drained.copy();
    }

    /**
     * Waits for the pool to drain after close.
     *
     * @param timeout maximum wait time
     * @return whether the pool drained before the timeout
     */
    public boolean awaitDrained(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        try {
            drained.get(Math.max(1, DurationSupport.saturatedMillis(timeout)), TimeUnit.MILLISECONDS);
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
        ArrayDeque<Worker> workersToClose = new ArrayDeque<>();
        synchronized (lock) {
            if (closing) {
                return;
            }
            closing = true;
            workersToClose.addAll(idle);
            size -= idle.size();
            idle.clear();
            lock.notifyAll();
        }
        while (!workersToClose.isEmpty()) {
            retireIdle(workersToClose.removeFirst(), PooledWorkerRetireReason.CLOSED);
        }
        completeDrainedIfReady();
    }

    private void warmup() {
        ArrayDeque<Worker> warmed = new ArrayDeque<>();
        try {
            for (int index = 0; index < options.warmupSize(); index++) {
                warmed.addLast(openWorker());
            }
        } catch (RuntimeException exception) {
            synchronized (lock) {
                failedStartups++;
            }
            while (!warmed.isEmpty()) {
                closeQuietly(warmed.removeFirst());
            }
            throw new PooledLineSessionException(
                    PooledLineSessionException.Reason.STARTUP_FAILED,
                    "Could not start pooled line-session worker",
                    exception);
        }
        synchronized (lock) {
            size += warmed.size();
            idle.addAll(warmed);
        }
    }

    private Worker acquire() {
        long started = System.nanoTime();
        long deadlineNanos = DurationSupport.deadlineFromNow(options.acquireTimeout());
        while (true) {
            Worker worker = takeOrReserveWorker(deadlineNanos);
            if (worker == null) {
                worker = openReservedWorker();
            }
            boolean healthy;
            try {
                healthy = isHealthy(worker, deadlineNanos);
            } catch (RuntimeException exception) {
                release(worker, false, PooledWorkerRetireReason.HEALTH_FAILED);
                throw exception;
            }
            if (healthy) {
                recordAcquireWait(started);
                return worker;
            }
            release(worker, false, PooledWorkerRetireReason.HEALTH_FAILED);
        }
    }

    private Worker takeOrReserveWorker(long deadlineNanos) {
        while (true) {
            Worker expired = null;
            PooledWorkerRetireReason expiredReason = null;
            synchronized (lock) {
                ensureOpen();
                Worker worker = idle.pollFirst();
                if (worker != null) {
                    PooledWorkerRetireReason reason = retireReasonForPolicy(worker);
                    if (reason != null) {
                        size--;
                        expired = worker;
                        expiredReason = reason;
                    } else {
                        leased++;
                        return worker;
                    }
                } else {
                    long remainingNanos = deadlineNanos - System.nanoTime();
                    if (remainingNanos <= 0) {
                        throw new PooledLineSessionException(
                                PooledLineSessionException.Reason.ACQUIRE_TIMEOUT,
                                "Timed out waiting for pooled line-session worker");
                    }
                    if (size < options.maxSize()) {
                        size++;
                        leased++;
                        return null;
                    }
                    try {
                        TimeUnit.NANOSECONDS.timedWait(lock, remainingNanos);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new PooledLineSessionException(
                                PooledLineSessionException.Reason.ACQUIRE_TIMEOUT,
                                "Interrupted while waiting for pooled line-session worker",
                                exception);
                    }
                }
            }
            if (expired != null) {
                retireIdle(expired, expiredReason);
            }
        }
    }

    private Worker openReservedWorker() {
        Worker worker;
        try {
            worker = openWorker();
        } catch (RuntimeException exception) {
            synchronized (lock) {
                leased--;
                size--;
                failedStartups++;
                lock.notifyAll();
            }
            completeDrainedIfReady();
            throw new PooledLineSessionException(
                    PooledLineSessionException.Reason.STARTUP_FAILED,
                    "Could not start pooled line-session worker",
                    exception);
        }
        synchronized (lock) {
            if (!closing) {
                return worker;
            }
        }
        release(worker, false, PooledWorkerRetireReason.CLOSED);
        throw closed();
    }

    private Worker openWorker() {
        synchronized (lock) {
            starting++;
        }
        long started = System.nanoTime();
        try {
            LineSession session = workerFactory.get();
            synchronized (lock) {
                created++;
                totalWorkerStartupNanos += System.nanoTime() - started;
            }
            return new Worker(session);
        } finally {
            synchronized (lock) {
                starting--;
                lock.notifyAll();
            }
        }
    }

    private boolean isHealthy(Worker worker, long acquireDeadlineNanos) {
        Duration timeout = WorkerHookSupport.boundedTimeout(options.hookTimeout(), acquireDeadlineNanos);
        if (timeout.isZero()) {
            throw new PooledLineSessionException(
                    PooledLineSessionException.Reason.ACQUIRE_TIMEOUT,
                    "Timed out waiting for pooled line-session health check");
        }
        return WorkerHookSupport.run(
                "icli-line-pool-health-",
                timeout,
                () -> options.healthCheck().test(worker.session),
                () -> new PooledLineSessionException(
                        PooledLineSessionException.Reason.HOOK_TIMEOUT, "Pooled line-session health check timed out"),
                exception -> new PooledLineSessionException(
                        PooledLineSessionException.Reason.WORKER_FAILED,
                        "Pooled line-session health check failed",
                        exception));
    }

    private void runReset(Worker worker) {
        WorkerHookSupport.run(
                "icli-line-pool-reset-",
                options.hookTimeout(),
                () -> {
                    options.resetHook().accept(worker.session);
                    return null;
                },
                () -> new PooledLineSessionException(
                        PooledLineSessionException.Reason.HOOK_TIMEOUT, "Pooled line-session reset hook timed out"),
                exception -> new PooledLineSessionException(
                        PooledLineSessionException.Reason.WORKER_FAILED,
                        "Pooled line-session reset hook failed",
                        exception));
    }

    private void release(Worker worker, boolean reusable, PooledWorkerRetireReason failureReason) {
        PooledWorkerRetireReason policyReason = reusable ? retireReasonForPolicy(worker) : null;
        if (policyReason != null) {
            reusable = false;
            failureReason = policyReason;
        }
        if (!reusable) {
            retire(worker, failureReason);
            return;
        }
        synchronized (lock) {
            leased--;
            if (closing) {
                leased++;
            } else {
                idle.addLast(worker);
                lock.notifyAll();
                return;
            }
        }
        retire(worker, PooledWorkerRetireReason.CLOSED);
    }

    private PooledWorkerRetireReason retireReasonForPolicy(Worker worker) {
        if (worker.requests >= options.maxRequestsPerWorker()) {
            return PooledWorkerRetireReason.MAX_REQUESTS;
        }
        Duration maxAge = options.maxWorkerAge();
        if (!maxAge.isZero() && System.nanoTime() - worker.createdAtNanos >= DurationSupport.saturatedNanos(maxAge)) {
            return PooledWorkerRetireReason.AGE;
        }
        return null;
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

    private void retireIdle(Worker worker, PooledWorkerRetireReason reason) {
        beginRetiring();
        try {
            closeQuietly(worker);
        } finally {
            finishRetiring(reason);
        }
        completeDrainedIfReady();
        replenishIfNeeded();
    }

    private void retire(Worker worker, PooledWorkerRetireReason reason) {
        beginRetiring();
        try {
            closeQuietly(worker);
        } finally {
            synchronized (lock) {
                leased--;
                size--;
            }
            finishRetiring(reason);
        }
        completeDrainedIfReady();
        replenishIfNeeded();
    }

    private void replenishIfNeeded() {
        if (!options.backgroundReplenishment() || options.minIdle() == 0) {
            return;
        }
        Threading.start("icli-line-pool-replenish-", this::replenishOnce);
    }

    private void replenishOnce() {
        synchronized (lock) {
            if (closing || idle.size() >= options.minIdle() || size >= options.maxSize()) {
                return;
            }
            size++;
        }
        Worker worker;
        try {
            worker = openWorker();
        } catch (RuntimeException exception) {
            synchronized (lock) {
                size--;
                failedStartups++;
                lock.notifyAll();
            }
            return;
        }
        synchronized (lock) {
            if (closing) {
                leased++;
            } else {
                idle.addLast(worker);
                lock.notifyAll();
                return;
            }
        }
        retire(worker, PooledWorkerRetireReason.CLOSED);
    }

    private void closeQuietly(Worker worker) {
        try {
            worker.session.close();
        } catch (RuntimeException ignored) {
            // Worker retirement must not hide the original request failure.
        }
    }

    private void beginRetiring() {
        synchronized (lock) {
            retiring++;
        }
    }

    private void finishRetiring(PooledWorkerRetireReason reason) {
        synchronized (lock) {
            retiring--;
            retired++;
            retireReasons.merge(reason, 1L, Long::sum);
            lock.notifyAll();
        }
    }

    private void completedRequests(long requestStarted) {
        synchronized (lock) {
            completedRequests++;
            recordRequestDuration(requestStarted);
        }
    }

    private void failedRequests(long requestStarted) {
        synchronized (lock) {
            failedRequests++;
            recordRequestDuration(requestStarted);
        }
    }

    private void recordRequestDuration(long requestStarted) {
        if (requestStarted != 0) {
            totalRequestDurationNanos += System.nanoTime() - requestStarted;
        }
    }

    private void recordAcquireWait(long started) {
        synchronized (lock) {
            totalAcquireWaitNanos += System.nanoTime() - started;
        }
    }

    private void completeDrainedIfReady() {
        synchronized (lock) {
            if (closing && size == 0 && leased == 0 && starting == 0 && retiring == 0) {
                drained.complete(null);
            }
            lock.notifyAll();
        }
    }

    private void ensureOpen() {
        if (closing) {
            throw closed();
        }
    }

    private PooledLineSessionException closed() {
        return new PooledLineSessionException(
                PooledLineSessionException.Reason.CLOSED, "Pooled line session is closed");
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

    private static final class Worker {
        private final LineSession session;
        private final long createdAtNanos = System.nanoTime();
        private int requests;

        private Worker(LineSession session) {
            this.session = Objects.requireNonNull(session, "session");
        }
    }
}
