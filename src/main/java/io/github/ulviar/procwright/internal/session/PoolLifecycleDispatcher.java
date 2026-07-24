/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.Threading;
import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Runs mandatory pool lifecycle work on a fixed set of owners.
 *
 * <p>A worker admission is reserved before its factory is invoked and returned after its physical retirement outcome
 * has been incorporated. Consequently non-cooperative closes apply backpressure to later worker admission. Retirement
 * batches need no additional admission because their number is already bounded by admitted workers. Reports and
 * replenishment owners use independent bounded admissions and owner sets. A task submitted recursively into its
 * current owner set runs inline, so bounded admission cannot make every owner wait for capacity that only those owners
 * can release.
 *
 * <p>All owners are started before this dispatcher becomes usable. A starter failure therefore fails construction
 * before any mandatory task can be accepted or abandoned.
 */
final class PoolLifecycleDispatcher {

    private static final int SHARED_PARALLELISM = 8;
    private static final int SHARED_WORKER_ADMISSION_CAPACITY = WorkerPoolSettings.MAX_SIZE;
    private static final int SHARED_TASK_ADMISSION_CAPACITY = WorkerPoolSettings.MAX_SIZE;
    private static final String WORKER_CLOSE_THREAD_PREFIX = "procwright-worker-close-";

    private final Object lock = new Object();
    private final ArrayDeque<TaskRequest> pending = new ArrayDeque<>();
    private final ArrayDeque<CompletableFuture<Void>> idleWaiters = new ArrayDeque<>();
    private final AdmissionPool admissions;
    private final ThreadLocal<Boolean> ownerThread = new ThreadLocal<>();
    private boolean ready;
    private boolean running = true;
    private int outstanding;

    PoolLifecycleDispatcher(BoundedTaskRunner.Limiter limiter, TaskStarter starter, String threadPrefix) {
        this(limiter, starter, threadPrefix, defaultAdmissionCapacity(limiter));
    }

    PoolLifecycleDispatcher(
            BoundedTaskRunner.Limiter limiter, TaskStarter starter, String threadPrefix, int admissionCapacity) {
        Objects.requireNonNull(limiter, "limiter");
        Objects.requireNonNull(starter, "starter");
        Objects.requireNonNull(threadPrefix, "threadPrefix");
        admissions = new AdmissionPool(admissionCapacity);
        int parallelism = limiter.availablePermits();
        if (parallelism <= 0) {
            throw new IllegalArgumentException("dispatcher limiter must have positive unused capacity");
        }
        startOwners(parallelism, starter, threadPrefix);
    }

    static Ownership execute(Runnable task) {
        return Retirements.INSTANCE.dispatch(task);
    }

    static Ownership executeRetirementBatch(Runnable task) {
        return Retirements.INSTANCE.dispatchRetirementBatch(task);
    }

    static Ownership replenish(Runnable task) {
        return Replenishments.INSTANCE.dispatch(task);
    }

    static Ownership execute(Admission admission, Runnable task) {
        return Retirements.INSTANCE.dispatch(admission, task);
    }

    static Ownership executeWorkerClose(Admission admission, Runnable task) {
        return executeWorkerClose(admission, task, Threading::start);
    }

    static Ownership executeWorkerClose(Admission admission, Runnable task, TaskStarter starter) {
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(task, "task");
        Objects.requireNonNull(starter, "starter");
        admission.claimDispatch();
        TaskRequest request = new TaskRequest(task);
        request.admission(admission, false);
        try {
            starter.start(WORKER_CLOSE_THREAD_PREFIX, () -> request.run(Thread.currentThread()));
        } catch (RuntimeException | Error failure) {
            admission.releaseDispatch();
            throw failure;
        }
        return request.ownership();
    }

    static Admission admit(long deadlineNanos) throws TimeoutException, InterruptedException {
        return Retirements.INSTANCE.admissions.acquire(deadlineNanos);
    }

    static int sharedWorkerAdmissionCapacity() {
        return SHARED_WORKER_ADMISSION_CAPACITY;
    }

    static Ownership report(Runnable task) {
        return Reports.INSTANCE.dispatch(task);
    }

    static CompletableFuture<Void> whenSharedIdle() {
        return CompletableFuture.allOf(
                Retirements.INSTANCE.whenIdle(), Reports.INSTANCE.whenIdle(), Replenishments.INSTANCE.whenIdle());
    }

    Ownership dispatch(Runnable task) {
        if (Boolean.TRUE.equals(ownerThread.get())) {
            return runInline(task);
        }
        Admission admission = admissions.acquireUninterruptibly();
        try {
            return dispatch(admission, task, true);
        } catch (RuntimeException | Error failure) {
            admission.close();
            throw failure;
        }
    }

    Ownership dispatchRetirementBatch(Runnable task) {
        TaskRequest request = new TaskRequest(Objects.requireNonNull(task, "task"));
        synchronized (lock) {
            if (!running || !ready) {
                throw new IllegalStateException("pool lifecycle dispatcher is unavailable");
            }
            pending.addLast(request);
            outstanding++;
            lock.notifyAll();
        }
        return request.ownership();
    }

    Ownership dispatch(Admission admission, Runnable task) {
        return dispatch(admission, task, false);
    }

    Admission tryAdmit() {
        return admissions.tryAcquire();
    }

    int availableAdmissions() {
        return admissions.availablePermits();
    }

    private Ownership dispatch(Admission admission, Runnable task, boolean closeAdmissionAfterTask) {
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(task, "task");
        admission.claimDispatch();
        TaskRequest request = new TaskRequest(task);
        synchronized (lock) {
            if (!running || !ready) {
                admission.releaseDispatch();
                throw new IllegalStateException("pool lifecycle dispatcher is unavailable");
            }
            request.admission(admission, closeAdmissionAfterTask);
            pending.addLast(request);
            outstanding++;
            lock.notifyAll();
        }
        return request.ownership();
    }

    CompletableFuture<Void> whenIdle() {
        synchronized (lock) {
            if (outstanding == 0) {
                return CompletableFuture.completedFuture(null);
            }
            CompletableFuture<Void> waiter = new CompletableFuture<>();
            idleWaiters.addLast(waiter);
            return waiter;
        }
    }

    private void startOwners(int parallelism, TaskStarter starter, String threadPrefix) {
        try {
            for (int index = 0; index < parallelism; index++) {
                starter.start(threadPrefix, this::runOwner);
            }
        } catch (RuntimeException | Error failure) {
            synchronized (lock) {
                running = false;
                ready = true;
                lock.notifyAll();
            }
            throw failure;
        }
        synchronized (lock) {
            ready = true;
            lock.notifyAll();
        }
    }

    private void runOwner() {
        boolean interrupted = false;
        ownerThread.set(Boolean.TRUE);
        try {
            while (true) {
                TaskRequest request;
                synchronized (lock) {
                    while (running && (!ready || pending.isEmpty())) {
                        try {
                            lock.wait();
                        } catch (InterruptedException ignored) {
                            interrupted = true;
                        }
                    }
                    if (!running) {
                        if (interrupted) {
                            Thread.currentThread().interrupt();
                        }
                        return;
                    }
                    request = pending.removeFirst();
                }
                request.run(Thread.currentThread());
                finishRequest();
            }
        } finally {
            ownerThread.remove();
        }
    }

    private Ownership runInline(Runnable task) {
        TaskRequest request = new TaskRequest(Objects.requireNonNull(task, "task"));
        request.run(Thread.currentThread());
        return request.ownership();
    }

    private void finishRequest() {
        List<CompletableFuture<Void>> completions;
        synchronized (lock) {
            outstanding--;
            if (outstanding == 0 && !idleWaiters.isEmpty()) {
                completions = new ArrayList<>(idleWaiters);
                idleWaiters.clear();
            } else {
                completions = List.of();
            }
        }
        completions.forEach(completion -> completion.complete(null));
    }

    private static PoolLifecycleDispatcher shared(String threadPrefix, int admissionCapacity) {
        return new PoolLifecycleDispatcher(
                new BoundedTaskRunner.Limiter(SHARED_PARALLELISM), Threading::start, threadPrefix, admissionCapacity);
    }

    private static final class Retirements {

        private static final PoolLifecycleDispatcher INSTANCE =
                shared("procwright-terminal-retirement-", SHARED_WORKER_ADMISSION_CAPACITY);
    }

    private static final class Reports {

        private static final PoolLifecycleDispatcher INSTANCE =
                shared("procwright-pool-late-report-", SHARED_TASK_ADMISSION_CAPACITY);
    }

    private static final class Replenishments {

        private static final PoolLifecycleDispatcher INSTANCE =
                shared("procwright-pool-replenishment-", SHARED_TASK_ADMISSION_CAPACITY);
    }

    private static int defaultAdmissionCapacity(BoundedTaskRunner.Limiter limiter) {
        return Math.max(16, Math.multiplyExact(limiter.availablePermits(), 8));
    }

    @FunctionalInterface
    interface TaskStarter {

        Thread start(String threadPrefix, Runnable task);
    }

    record Ownership(
            CompletableFuture<Thread> started,
            CompletableFuture<Void> completion,
            CompletableFuture<Void> cleanupCompletion) {

        Ownership(CompletableFuture<Thread> started, CompletableFuture<Void> completion) {
            this(started, completion, completion);
        }

        Ownership {
            Objects.requireNonNull(started, "started");
            Objects.requireNonNull(completion, "completion");
            Objects.requireNonNull(cleanupCompletion, "cleanupCompletion");
        }
    }

    static final class Admission implements AutoCloseable {

        private final AdmissionPool owner;
        private final AtomicBoolean returned = new AtomicBoolean();
        private boolean dispatchActive;
        private boolean closeRequested;

        private Admission(AdmissionPool owner) {
            this.owner = owner;
        }

        synchronized void claimDispatch() {
            if (returned.get() || closeRequested) {
                throw new IllegalStateException("pool lifecycle admission is closed");
            }
            if (dispatchActive) {
                throw new IllegalStateException("pool lifecycle admission already owns a task");
            }
            dispatchActive = true;
        }

        void releaseDispatch() {
            boolean release;
            synchronized (this) {
                if (!dispatchActive) {
                    throw new IllegalStateException("pool lifecycle admission owns no task");
                }
                dispatchActive = false;
                release = closeRequested;
            }
            if (release) {
                returnToOwner();
            }
        }

        @Override
        public void close() {
            boolean release;
            synchronized (this) {
                closeRequested = true;
                release = !dispatchActive;
            }
            if (release) {
                returnToOwner();
            }
        }

        private void returnToOwner() {
            if (returned.compareAndSet(false, true)) {
                owner.release();
            }
        }
    }

    static final class AdmissionPool {

        private final Semaphore permits;

        AdmissionPool(int capacity) {
            if (capacity <= 0) {
                throw new IllegalArgumentException("admission capacity must be positive");
            }
            permits = new Semaphore(capacity, true);
        }

        Admission acquire(long deadlineNanos) throws TimeoutException, InterruptedException {
            long remainingNanos = deadlineNanos - System.nanoTime();
            if (remainingNanos <= 0 || !permits.tryAcquire(remainingNanos, TimeUnit.NANOSECONDS)) {
                throw new TimeoutException("pool lifecycle capacity was not available before its deadline");
            }
            return new Admission(this);
        }

        Admission acquireUninterruptibly() {
            permits.acquireUninterruptibly();
            return new Admission(this);
        }

        Admission tryAcquire() {
            return permits.tryAcquire() ? new Admission(this) : null;
        }

        int availablePermits() {
            return permits.availablePermits();
        }

        private void release() {
            permits.release();
        }
    }

    private static final class TaskRequest {

        private final Runnable task;
        private final CompletableFuture<Thread> started = new CompletableFuture<>();
        private final CompletableFuture<Void> completion = new CompletableFuture<>();
        private Admission admission;
        private boolean closeAdmissionAfterTask;

        private TaskRequest(Runnable task) {
            this.task = task;
        }

        private Ownership ownership() {
            return new Ownership(started, completion);
        }

        private void admission(Admission value, boolean closeAfterTask) {
            admission = Objects.requireNonNull(value, "admission");
            closeAdmissionAfterTask = closeAfterTask;
        }

        private void run(Thread owner) {
            started.complete(Objects.requireNonNull(owner, "owner"));
            Throwable failure = null;
            try {
                task.run();
            } catch (Throwable taskFailure) {
                failure = taskFailure;
            } finally {
                Admission ownedAdmission = admission;
                if (ownedAdmission != null) {
                    ownedAdmission.releaseDispatch();
                    if (closeAdmissionAfterTask) {
                        ownedAdmission.close();
                    }
                }
            }
            if (failure == null) {
                completion.complete(null);
            } else {
                completion.completeExceptionally(failure);
            }
        }
    }
}
