package com.github.ulviar.icli.internal.session;

import com.github.ulviar.icli.internal.DurationSupport;
import com.github.ulviar.icli.internal.Threading;
import com.github.ulviar.icli.session.PooledProtocolSession;
import com.github.ulviar.icli.session.PooledProtocolSessionException;
import com.github.ulviar.icli.session.PooledProtocolSessionInvocation;
import com.github.ulviar.icli.session.PooledProtocolSessionMetrics;
import com.github.ulviar.icli.session.PooledProtocolSessionOptions;
import com.github.ulviar.icli.session.PooledWorkerRetireReason;
import com.github.ulviar.icli.session.ProtocolSession;
import com.github.ulviar.icli.session.ProtocolSessionException;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;
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

    private final Supplier<ProtocolSession<I, O>> workerFactory;
    private final PooledProtocolSessionInvocation<I, O> invocation;
    private final PooledProtocolSessionOptions options;
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

    public DefaultPooledProtocolSession(
            Supplier<ProtocolSession<I, O>> workerFactory, PooledProtocolSessionInvocation<I, O> invocation) {
        this.workerFactory = Objects.requireNonNull(workerFactory, "workerFactory");
        this.invocation = Objects.requireNonNull(invocation, "invocation");
        this.options = invocation.options();
        warmup();
    }

    @Override
    public O request(I request) {
        Worker worker = null;
        boolean reusable = false;
        PooledWorkerRetireReason retireReason = PooledWorkerRetireReason.WORKER_FAILED;
        long requestStarted = 0;
        try {
            worker = acquire();
            requestStarted = System.nanoTime();
            O response = worker.session.request(request);
            worker.requests++;
            runReset(worker);
            completedRequests(requestStarted);
            reusable = true;
            return response;
        } catch (ProtocolSessionException exception) {
            retireReason = retireReasonFor(exception);
            failedRequests(requestStarted);
            throw exception;
        } catch (PooledProtocolSessionException exception) {
            retireReason = retireReasonFor(exception);
            failedRequests(requestStarted);
            throw exception;
        } catch (RuntimeException exception) {
            failedRequests(requestStarted);
            throw new PooledProtocolSessionException(
                    PooledProtocolSessionException.Reason.WORKER_FAILED,
                    "Pooled protocol-session worker failed",
                    exception);
        } finally {
            if (worker != null) {
                release(worker, reusable, retireReason);
            }
        }
    }

    @Override
    public O request(I request, Duration timeout) {
        requirePositive(timeout, "timeout");
        Worker worker = null;
        boolean reusable = false;
        PooledWorkerRetireReason retireReason = PooledWorkerRetireReason.WORKER_FAILED;
        long requestStarted = 0;
        try {
            worker = acquire();
            requestStarted = System.nanoTime();
            O response = worker.session.request(request, timeout);
            worker.requests++;
            runReset(worker);
            completedRequests(requestStarted);
            reusable = true;
            return response;
        } catch (ProtocolSessionException exception) {
            retireReason = retireReasonFor(exception);
            failedRequests(requestStarted);
            throw exception;
        } catch (PooledProtocolSessionException exception) {
            retireReason = retireReasonFor(exception);
            failedRequests(requestStarted);
            throw exception;
        } catch (RuntimeException exception) {
            failedRequests(requestStarted);
            throw new PooledProtocolSessionException(
                    PooledProtocolSessionException.Reason.WORKER_FAILED,
                    "Pooled protocol-session worker failed",
                    exception);
        } finally {
            if (worker != null) {
                release(worker, reusable, retireReason);
            }
        }
    }

    @Override
    public PooledProtocolSessionMetrics metrics() {
        synchronized (lock) {
            return new PooledProtocolSessionMetrics(
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

    @Override
    public CompletableFuture<Void> onDrained() {
        return drained.copy();
    }

    @Override
    public boolean awaitDrained(Duration timeout) {
        requirePositive(timeout, "timeout");
        try {
            drained.get(DurationSupport.saturatedMillis(timeout), TimeUnit.MILLISECONDS);
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
            throw new PooledProtocolSessionException(
                    PooledProtocolSessionException.Reason.STARTUP_FAILED,
                    "Could not start pooled protocol-session worker",
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
                        throw new PooledProtocolSessionException(
                                PooledProtocolSessionException.Reason.ACQUIRE_TIMEOUT,
                                "Timed out waiting for pooled protocol-session worker");
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
                        throw new PooledProtocolSessionException(
                                PooledProtocolSessionException.Reason.ACQUIRE_TIMEOUT,
                                "Interrupted while waiting for pooled protocol-session worker",
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
            throw new PooledProtocolSessionException(
                    PooledProtocolSessionException.Reason.STARTUP_FAILED,
                    "Could not start pooled protocol-session worker",
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
        long started = System.nanoTime();
        synchronized (lock) {
            starting++;
        }
        try {
            ProtocolSession<I, O> session = workerFactory.get();
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
            throw new PooledProtocolSessionException(
                    PooledProtocolSessionException.Reason.ACQUIRE_TIMEOUT,
                    "Timed out waiting for pooled protocol-session health check");
        }
        return WorkerHookSupport.run(
                "icli-protocol-pool-health-",
                timeout,
                () -> invocation.healthCheck().test(worker.session),
                () -> new PooledProtocolSessionException(
                        PooledProtocolSessionException.Reason.HOOK_TIMEOUT,
                        "Pooled protocol-session health check timed out"),
                exception -> new PooledProtocolSessionException(
                        PooledProtocolSessionException.Reason.WORKER_FAILED,
                        "Pooled protocol-session health check failed",
                        exception));
    }

    private void runReset(Worker worker) {
        WorkerHookSupport.run(
                "icli-protocol-pool-reset-",
                options.hookTimeout(),
                () -> {
                    invocation.resetHook().accept(worker.session);
                    return null;
                },
                () -> new PooledProtocolSessionException(
                        PooledProtocolSessionException.Reason.HOOK_TIMEOUT,
                        "Pooled protocol-session reset hook timed out"),
                exception -> new PooledProtocolSessionException(
                        PooledProtocolSessionException.Reason.WORKER_FAILED,
                        "Pooled protocol-session reset hook failed",
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
        Threading.start("icli-protocol-pool-replenish-", this::replenishOnce);
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

    private PooledProtocolSessionException closed() {
        return new PooledProtocolSessionException(
                PooledProtocolSessionException.Reason.CLOSED, "Pooled protocol session is closed");
    }

    private static Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private final class Worker {
        private final ProtocolSession<I, O> session;
        private final long createdAtNanos = System.nanoTime();
        private int requests;

        private Worker(ProtocolSession<I, O> session) {
            this.session = Objects.requireNonNull(session, "session");
        }
    }
}
