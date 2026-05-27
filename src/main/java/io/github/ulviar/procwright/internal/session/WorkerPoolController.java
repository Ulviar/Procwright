package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.DurationSupport;
import io.github.ulviar.procwright.internal.Threading;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class WorkerPoolController<S> {

    private final Supplier<S> workerFactory;
    private final Consumer<S> workerCloser;
    private final PoolOptions options;
    private final FailureFactory failures;
    private final String workerLabel;
    private final String replenishThreadPrefix;
    private final ArrayDeque<Worker<S>> idle = new ArrayDeque<>();
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

    WorkerPoolController(
            Supplier<S> workerFactory,
            Consumer<S> workerCloser,
            PoolOptions options,
            FailureFactory failures,
            String workerLabel,
            String replenishThreadPrefix) {
        this.workerFactory = Objects.requireNonNull(workerFactory, "workerFactory");
        this.workerCloser = Objects.requireNonNull(workerCloser, "workerCloser");
        this.options = Objects.requireNonNull(options, "options");
        this.failures = Objects.requireNonNull(failures, "failures");
        this.workerLabel = Objects.requireNonNull(workerLabel, "workerLabel");
        this.replenishThreadPrefix = Objects.requireNonNull(replenishThreadPrefix, "replenishThreadPrefix");
        warmup();
    }

    Worker<S> acquire(HealthCheck<S> healthCheck) {
        Objects.requireNonNull(healthCheck, "healthCheck");
        long started = System.nanoTime();
        long deadlineNanos = DurationSupport.deadlineFromNow(options.acquireTimeout());
        while (true) {
            Worker<S> worker = takeOrReserveWorker(deadlineNanos);
            if (worker == null) {
                worker = openReservedWorker();
            }
            boolean healthy;
            try {
                healthy = healthCheck.test(worker.session(), deadlineNanos);
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

    void release(Worker<S> worker, boolean reusable, PooledWorkerRetireReason failureReason) {
        Objects.requireNonNull(worker, "worker");
        PooledWorkerRetireReason policyReason = reusable ? retireReasonForPolicy(worker) : null;
        if (policyReason != null) {
            reusable = false;
            failureReason = policyReason;
        }
        if (!reusable) {
            retire(worker, Objects.requireNonNull(failureReason, "failureReason"));
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

    MetricsSnapshot metrics() {
        synchronized (lock) {
            return new MetricsSnapshot(
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

    CompletableFuture<Void> onDrained() {
        return drained.copy();
    }

    void close() {
        ArrayDeque<Worker<S>> workersToClose = new ArrayDeque<>();
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

    void completedRequests(long requestStarted) {
        synchronized (lock) {
            completedRequests++;
            recordRequestDuration(requestStarted);
        }
    }

    void failedRequests(long requestStarted) {
        synchronized (lock) {
            failedRequests++;
            recordRequestDuration(requestStarted);
        }
    }

    private void warmup() {
        ArrayDeque<Worker<S>> warmed = new ArrayDeque<>();
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
            throw failures.startupFailed("Could not start " + workerLabel, exception);
        }
        synchronized (lock) {
            size += warmed.size();
            idle.addAll(warmed);
        }
    }

    private Worker<S> takeOrReserveWorker(long deadlineNanos) {
        while (true) {
            Worker<S> expired = null;
            PooledWorkerRetireReason expiredReason = null;
            synchronized (lock) {
                ensureOpen();
                Worker<S> worker = idle.pollFirst();
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
                        throw failures.acquireTimeout("Timed out waiting for " + workerLabel);
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
                        throw failures.acquireInterrupted("Interrupted while waiting for " + workerLabel, exception);
                    }
                }
            }
            if (expired != null) {
                retireIdle(expired, expiredReason);
            }
        }
    }

    private Worker<S> openReservedWorker() {
        Worker<S> worker;
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
            throw failures.startupFailed("Could not start " + workerLabel, exception);
        }
        synchronized (lock) {
            if (!closing) {
                return worker;
            }
        }
        release(worker, false, PooledWorkerRetireReason.CLOSED);
        throw failures.closed("Pool is closed");
    }

    private Worker<S> openWorker() {
        long started = System.nanoTime();
        synchronized (lock) {
            starting++;
        }
        try {
            S session = Objects.requireNonNull(workerFactory.get(), "workerFactory returned null");
            synchronized (lock) {
                created++;
                totalWorkerStartupNanos += System.nanoTime() - started;
            }
            return new Worker<>(session);
        } finally {
            synchronized (lock) {
                starting--;
                lock.notifyAll();
            }
        }
    }

    private PooledWorkerRetireReason retireReasonForPolicy(Worker<S> worker) {
        if (worker.requests() >= options.maxRequestsPerWorker()) {
            return PooledWorkerRetireReason.MAX_REQUESTS;
        }
        Duration maxAge = options.maxWorkerAge();
        if (!maxAge.isZero() && System.nanoTime() - worker.createdAtNanos() >= DurationSupport.saturatedNanos(maxAge)) {
            return PooledWorkerRetireReason.AGE;
        }
        return null;
    }

    private void retireIdle(Worker<S> worker, PooledWorkerRetireReason reason) {
        beginRetiring();
        try {
            closeQuietly(worker);
        } finally {
            finishRetiring(reason);
        }
        completeDrainedIfReady();
        replenishIfNeeded();
    }

    private void retire(Worker<S> worker, PooledWorkerRetireReason reason) {
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
        Threading.start(replenishThreadPrefix, this::replenishOnce);
    }

    private void replenishOnce() {
        synchronized (lock) {
            if (closing || idle.size() >= options.minIdle() || size >= options.maxSize()) {
                return;
            }
            size++;
        }
        Worker<S> worker;
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

    private void closeQuietly(Worker<S> worker) {
        try {
            workerCloser.accept(worker.session());
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
            throw failures.closed("Pool is closed");
        }
    }

    interface PoolOptions {

        int maxSize();

        int warmupSize();

        int minIdle();

        Duration acquireTimeout();

        int maxRequestsPerWorker();

        Duration maxWorkerAge();

        boolean backgroundReplenishment();
    }

    interface FailureFactory {

        RuntimeException closed(String message);

        RuntimeException acquireTimeout(String message);

        RuntimeException acquireInterrupted(String message, InterruptedException cause);

        RuntimeException startupFailed(String message, Throwable cause);
    }

    interface HealthCheck<S> {

        boolean test(S session, long acquireDeadlineNanos);
    }

    record MetricsSnapshot(
            int size,
            int idle,
            int leased,
            int starting,
            int retiring,
            long created,
            long retired,
            long completedRequests,
            long failedRequests,
            long failedStartups,
            long totalAcquireWaitNanos,
            long totalRequestDurationNanos,
            long totalWorkerStartupNanos,
            Map<PooledWorkerRetireReason, Long> retireReasons) {}

    static final class Worker<S> {

        private final S session;
        private final long createdAtNanos = System.nanoTime();
        private int requests;

        private Worker(S session) {
            this.session = Objects.requireNonNull(session, "session");
        }

        S session() {
            return session;
        }

        long createdAtNanos() {
            return createdAtNanos;
        }

        int requests() {
            return requests;
        }

        void recordRequest() {
            requests++;
        }
    }
}
