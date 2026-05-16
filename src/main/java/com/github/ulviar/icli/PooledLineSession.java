package com.github.ulviar.icli;

import java.time.Duration;
import java.util.ArrayDeque;
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
public final class PooledLineSession implements AutoCloseable {

    private final Supplier<LineSession> workerFactory;
    private final PooledLineSessionOptions options;
    private final ArrayDeque<Worker> idle = new ArrayDeque<>();
    private final Object lock = new Object();
    private final CompletableFuture<Void> drained = new CompletableFuture<>();

    private boolean closing;
    private int size;
    private int leased;
    private long created;
    private long retired;
    private long completedRequests;
    private long failedRequests;

    PooledLineSession(Supplier<LineSession> workerFactory, PooledLineSessionOptions options) {
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
        try {
            worker = acquire();
            LineResponse response = worker.session.request(line);
            worker.requests++;
            runReset(worker);
            completedRequests();
            reusable = true;
            return response;
        } catch (LineSessionException exception) {
            failedRequests();
            throw exception;
        } catch (PooledLineSessionException exception) {
            failedRequests();
            throw exception;
        } catch (RuntimeException exception) {
            failedRequests();
            throw new PooledLineSessionException(
                    PooledLineSessionException.Reason.WORKER_FAILED, "Pooled line-session worker failed", exception);
        } finally {
            if (worker != null) {
                release(worker, reusable);
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
        try {
            worker = acquire();
            LineResponse response = worker.session.request(line, timeout);
            worker.requests++;
            runReset(worker);
            completedRequests();
            reusable = true;
            return response;
        } catch (LineSessionException exception) {
            failedRequests();
            throw exception;
        } catch (PooledLineSessionException exception) {
            failedRequests();
            throw exception;
        } catch (RuntimeException exception) {
            failedRequests();
            throw new PooledLineSessionException(
                    PooledLineSessionException.Reason.WORKER_FAILED, "Pooled line-session worker failed", exception);
        } finally {
            if (worker != null) {
                release(worker, reusable);
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
                    size, idle.size(), leased, created, retired, completedRequests, failedRequests);
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
            drained.get(Math.max(1, timeout.toMillis()), TimeUnit.MILLISECONDS);
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
            retireIdle(workersToClose.removeFirst());
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
            while (!warmed.isEmpty()) {
                closeQuietly(warmed.removeFirst());
            }
            throw exception;
        }
        synchronized (lock) {
            size += warmed.size();
            idle.addAll(warmed);
        }
    }

    private Worker acquire() {
        long deadlineNanos = deadlineFromNow(options.acquireTimeout());
        while (true) {
            Worker worker = takeOrReserveWorker(deadlineNanos);
            if (worker == null) {
                worker = openReservedWorker();
            }
            boolean healthy;
            try {
                healthy = isHealthy(worker);
            } catch (RuntimeException exception) {
                release(worker, false);
                throw exception;
            }
            if (healthy) {
                return worker;
            }
            release(worker, false);
        }
    }

    private Worker takeOrReserveWorker(long deadlineNanos) {
        while (true) {
            Worker expired = null;
            synchronized (lock) {
                ensureOpen();
                Worker worker = idle.pollFirst();
                if (worker != null) {
                    if (shouldRetire(worker)) {
                        size--;
                        retired++;
                        lock.notifyAll();
                        expired = worker;
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
                closeQuietly(expired);
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
                lock.notifyAll();
            }
            completeDrainedIfReady();
            throw exception;
        }
        synchronized (lock) {
            if (!closing) {
                return worker;
            }
        }
        release(worker, false);
        throw closed();
    }

    private Worker openWorker() {
        LineSession session = workerFactory.get();
        synchronized (lock) {
            created++;
        }
        return new Worker(session);
    }

    private boolean isHealthy(Worker worker) {
        try {
            return options.healthCheck().test(worker.session);
        } catch (RuntimeException exception) {
            throw new PooledLineSessionException(
                    PooledLineSessionException.Reason.WORKER_FAILED,
                    "Pooled line-session health check failed",
                    exception);
        }
    }

    private void runReset(Worker worker) {
        try {
            options.resetHook().accept(worker.session);
        } catch (RuntimeException exception) {
            throw new PooledLineSessionException(
                    PooledLineSessionException.Reason.WORKER_FAILED,
                    "Pooled line-session reset hook failed",
                    exception);
        }
    }

    private void release(Worker worker, boolean reusable) {
        if (reusable && shouldRetire(worker)) {
            reusable = false;
        }
        if (!reusable) {
            retire(worker);
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
        retire(worker);
    }

    private void retireIdle(Worker worker) {
        closeQuietly(worker);
        workersRetired();
        completeDrainedIfReady();
    }

    private boolean shouldRetire(Worker worker) {
        if (worker.requests >= options.maxRequestsPerWorker()) {
            return true;
        }
        Duration maxAge = options.maxWorkerAge();
        return !maxAge.isZero() && System.nanoTime() - worker.createdAtNanos >= saturatedNanos(maxAge);
    }

    private void retire(Worker worker) {
        closeQuietly(worker);
        synchronized (lock) {
            leased--;
            size--;
            retired++;
            lock.notifyAll();
        }
        completeDrainedIfReady();
    }

    private void closeQuietly(Worker worker) {
        try {
            worker.session.close();
        } catch (RuntimeException ignored) {
            // Worker retirement must not hide the original request failure.
        }
    }

    private void completedRequests() {
        synchronized (lock) {
            completedRequests++;
        }
    }

    private void failedRequests() {
        synchronized (lock) {
            failedRequests++;
        }
    }

    private void workersRetired() {
        synchronized (lock) {
            retired++;
            lock.notifyAll();
        }
    }

    private void completeDrainedIfReady() {
        synchronized (lock) {
            if (closing && size == 0 && leased == 0) {
                drained.complete(null);
            }
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

    private static long saturatedNanos(Duration duration) {
        try {
            return duration.toNanos();
        } catch (ArithmeticException ignored) {
            return Long.MAX_VALUE;
        }
    }

    private static long deadlineFromNow(Duration duration) {
        long now = System.nanoTime();
        long nanos = saturatedNanos(duration);
        if (Long.MAX_VALUE - now < nanos) {
            return Long.MAX_VALUE;
        }
        return now + nanos;
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
