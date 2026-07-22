/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * Owns globally bounded physical stream closes after accepting cleanup responsibility in advance.
 *
 * <p>Reservations, queued requests, and active closes share one hard bound. A caller reserves before publishing the
 * resource, so an accepted close cannot later be rejected. A fallback owner is running before a reservation is
 * returned, ensuring that a later close-worker start failure never transfers physical cleanup to the caller thread.
 * Physical close and callbacks always run outside the dispatcher monitor.
 *
 * @hidden
 */
public final class BoundedCloseDispatcher {

    public static final int SHARED_ACTIVE_CAPACITY = 32;
    public static final int SHARED_PENDING_CAPACITY = 128;
    public static final int SHARED_MAX_OUTSTANDING_CAPACITY = SHARED_ACTIVE_CAPACITY + SHARED_PENDING_CAPACITY;

    private static final PermitReleaser RELEASE_PERMIT = Permit::release;
    private static final BoundedCloseDispatcher SHARED = new BoundedCloseDispatcher(
            SHARED_ACTIVE_CAPACITY, SHARED_PENDING_CAPACITY, SHARED_MAX_OUTSTANDING_CAPACITY);

    private final int activeCapacity;
    private final int pendingCapacity;
    private final int maxOutstandingCapacity;
    private final ThreadStarter threadStarter;
    private final BoundedFailureReporter failureReporter;
    private final Object lock = new Object();
    private final ArrayDeque<CloseExecution> pending;
    private final ArrayDeque<CloseExecution> fallbackPending;
    private int active;
    private int outstanding;
    private boolean fallbackOwnerRunning;

    public BoundedCloseDispatcher(int activeCapacity, int pendingCapacity, int maxOutstandingCapacity) {
        this(activeCapacity, pendingCapacity, maxOutstandingCapacity, Threading::start);
    }

    public BoundedCloseDispatcher(
            int activeCapacity, int pendingCapacity, int maxOutstandingCapacity, ThreadStarter threadStarter) {
        this(activeCapacity, pendingCapacity, maxOutstandingCapacity, threadStarter, BoundedFailureReporter.shared());
    }

    public BoundedCloseDispatcher(
            int activeCapacity,
            int pendingCapacity,
            int maxOutstandingCapacity,
            ThreadStarter threadStarter,
            BoundedFailureReporter failureReporter) {
        if (activeCapacity <= 0) {
            throw new IllegalArgumentException("activeCapacity must be positive");
        }
        if (pendingCapacity <= 0) {
            throw new IllegalArgumentException("pendingCapacity must be positive");
        }
        int totalCapacity;
        try {
            totalCapacity = Math.addExact(activeCapacity, pendingCapacity);
        } catch (ArithmeticException overflow) {
            throw new IllegalArgumentException("dispatcher capacities are too large", overflow);
        }
        if (maxOutstandingCapacity != totalCapacity) {
            throw new IllegalArgumentException("maxOutstandingCapacity must equal activeCapacity + pendingCapacity");
        }
        this.activeCapacity = activeCapacity;
        this.pendingCapacity = pendingCapacity;
        this.maxOutstandingCapacity = maxOutstandingCapacity;
        this.threadStarter = Objects.requireNonNull(threadStarter, "threadStarter");
        this.failureReporter = Objects.requireNonNull(failureReporter, "failureReporter");
        pending = new ArrayDeque<>(pendingCapacity);
        fallbackPending = new ArrayDeque<>(maxOutstandingCapacity);
    }

    public static BoundedCloseDispatcher shared() {
        return SHARED;
    }

    public Reservation reserve(int permits) {
        if (permits <= 0 || permits > maxOutstandingCapacity) {
            throw new IllegalArgumentException("permits must be between 1 and " + maxOutstandingCapacity);
        }
        synchronized (lock) {
            if (outstanding > maxOutstandingCapacity - permits) {
                throw new RejectedExecutionException("Stream close capacity is exhausted: "
                        + outstanding
                        + " of "
                        + maxOutstandingCapacity
                        + " cleanup resources are reserved, active, or pending");
            }
            outstanding += permits;
            try {
                ensureFallbackOwnerLocked();
            } catch (RuntimeException | Error startFailure) {
                outstanding -= permits;
                lock.notifyAll();
                throw startFailure;
            }
        }
        return new Reservation(this, permits);
    }

    public static CloseRequest closeRequest(
            Closeable closeable, String threadPrefix, Consumer<? super Throwable> failureHandler) {
        return closeRequest(closeable, threadPrefix, failureHandler, () -> {});
    }

    public static CloseRequest closeRequest(
            Closeable closeable,
            String threadPrefix,
            Consumer<? super Throwable> failureHandler,
            Runnable completionHandler) {
        return new CloseRequest(closeable, threadPrefix, ignored -> {}, failureHandler, completionHandler);
    }

    static CloseRequest ownedCloseRequest(
            Closeable closeable,
            String threadPrefix,
            Consumer<? super Throwable> settlement,
            Consumer<? super Throwable> failureHandler,
            Runnable completionHandler) {
        return new CloseRequest(closeable, threadPrefix, settlement, failureHandler, completionHandler);
    }

    private DispatchOutcome dispatchReserved(Permit permit, CloseRequest request) {
        Objects.requireNonNull(permit, "permit");
        CloseExecution execution = new CloseExecution(request);
        CloseExecution launch;
        synchronized (lock) {
            if (permit.owner != this) {
                throw new IllegalArgumentException("Close permit must belong to this dispatcher");
            }
            requireAdmissionCapacityLocked(1);
            if (!permit.consumed.compareAndSet(false, true)) {
                throw new IllegalStateException("Stream close permit has already been consumed");
            }
            launch = admitLocked(execution);
        }
        startExecution(launch);
        return DispatchOutcome.accepted(execution.startFailure());
    }

    private DispatchOutcome dispatchReservedPair(
            Permit firstPermit, CloseRequest first, Permit secondPermit, CloseRequest second) {
        Objects.requireNonNull(firstPermit, "firstPermit");
        Objects.requireNonNull(first, "first");
        Objects.requireNonNull(secondPermit, "secondPermit");
        Objects.requireNonNull(second, "second");
        CloseExecution firstExecution = new CloseExecution(first);
        CloseExecution secondExecution = new CloseExecution(second);
        List<CloseExecution> launches = new ArrayList<>(2);
        synchronized (lock) {
            if (firstPermit.owner != this || secondPermit.owner != this) {
                throw new IllegalArgumentException("Both close permits must belong to this dispatcher");
            }
            requireAdmissionCapacityLocked(2);
            if (!firstPermit.consumed.compareAndSet(false, true)) {
                throw new IllegalStateException("First stream close permit has already been consumed");
            }
            if (!secondPermit.consumed.compareAndSet(false, true)) {
                firstPermit.consumed.set(false);
                throw new IllegalStateException("Second stream close permit has already been consumed");
            }
            addLaunch(launches, admitLocked(firstExecution));
            addLaunch(launches, admitLocked(secondExecution));
        }
        launches.forEach(this::startExecution);
        Throwable failure = firstExecution.startFailure();
        failure = SuppressionSupport.combine(failure, secondExecution.startFailure());
        return DispatchOutcome.accepted(failure);
    }

    private void requireAdmissionCapacityLocked(int requests) {
        if (active < activeCapacity && !pending.isEmpty()) {
            throw new IllegalStateException("Pending stream close exists while active close capacity is available");
        }
        int available = activeCapacity - active + pendingCapacity - pending.size();
        if (requests > available) {
            throw new IllegalStateException("Reserved stream close exceeds dispatcher execution capacity");
        }
    }

    private CloseExecution admitLocked(CloseExecution execution) {
        if (active < activeCapacity) {
            active++;
            return execution;
        }
        pending.addLast(execution);
        return null;
    }

    private static void addLaunch(List<CloseExecution> launches, CloseExecution execution) {
        if (execution != null) {
            launches.add(execution);
        }
    }

    private void releaseReserved(int permits) {
        if (permits == 0) {
            return;
        }
        synchronized (lock) {
            if (permits < 0 || outstanding < permits) {
                throw new IllegalStateException("Stream close dispatcher reservation accounting underflow");
            }
            outstanding -= permits;
            lock.notifyAll();
        }
    }

    private void startExecution(CloseExecution execution) {
        if (execution == null) {
            return;
        }
        WorkerStartGate startGate = new WorkerStartGate(execution, Thread.currentThread());
        try {
            Thread owner = Objects.requireNonNull(
                    threadStarter.start(execution.request().threadPrefix(), startGate::run),
                    "close thread starter returned null");
            startGate.accept(owner);
        } catch (RuntimeException | Error startFailure) {
            startGate.reject();
            execution.recordStartFailure(startFailure);
            if (!handoffAfterStartFailure(execution)) {
                publishFailure(execution.request(), startFailure, Thread.currentThread());
            }
        }
    }

    private static Throwable attemptPhysicalClose(Closeable closeable) {
        try {
            closeable.close();
            return null;
        } catch (IOException | RuntimeException | Error failure) {
            return failure;
        }
    }

    private void ensureFallbackOwnerLocked() {
        if (fallbackOwnerRunning) {
            return;
        }
        fallbackOwnerRunning = true;
        try {
            Threading.start("procwright-close-fallback-", this::runFallbackOwner);
        } catch (RuntimeException | Error startFailure) {
            fallbackOwnerRunning = false;
            throw startFailure;
        }
    }

    private boolean handoffAfterStartFailure(CloseExecution execution) {
        if (!execution.claimAfterStartFailure()) {
            return false;
        }
        synchronized (lock) {
            fallbackPending.addLast(execution);
            lock.notifyAll();
        }
        return true;
    }

    private void runFallbackOwner() {
        boolean restoreInterrupt = false;
        try {
            while (true) {
                CloseExecution execution;
                synchronized (lock) {
                    while (fallbackPending.isEmpty()) {
                        if (outstanding == 0) {
                            fallbackOwnerRunning = false;
                            lock.notifyAll();
                            return;
                        }
                        try {
                            lock.wait();
                        } catch (InterruptedException interruption) {
                            restoreInterrupt = true;
                        }
                    }
                    execution = fallbackPending.removeFirst();
                }
                execution.runClaimedAfterStartFailure();
            }
        } finally {
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private void completeActiveLocked() {
        if (active <= 0 || outstanding <= 0) {
            throw new IllegalStateException("Stream close dispatcher accounting underflow");
        }
        active--;
        outstanding--;
    }

    private void completeExecution(CloseExecution execution, Throwable failure) {
        startExecution(completeExecutionSlotAndClaimNext());
        CloseRequest request = execution.request();
        Throwable settledFailure = settle(request, failure);
        Thread sourceThread = Thread.currentThread();
        publishFailure(request, settledFailure, sourceThread);
        publishCompletion(request, sourceThread);
    }

    private CloseExecution completeExecutionSlotAndClaimNext() {
        synchronized (lock) {
            completeActiveLocked();
            CloseExecution next = pending.pollFirst();
            if (next != null) {
                active++;
            }
            lock.notifyAll();
            return next;
        }
    }

    private static Throwable settle(CloseRequest request, Throwable failure) {
        try {
            request.settlement().accept(failure);
            return failure;
        } catch (Throwable settlementFailure) {
            return SuppressionSupport.combine(failure, settlementFailure);
        }
    }

    private void publishFailure(CloseRequest request, Throwable failure, Thread sourceThread) {
        if (failure != null) {
            failureReporter.execute(sourceThread, () -> {
                try {
                    request.failureHandler().accept(failure);
                } catch (Throwable callbackFailure) {
                    SuppressionSupport.attach(failure, callbackFailure);
                    failureReporter.report(sourceThread, failure);
                }
            });
        }
    }

    private void publishCompletion(CloseRequest request, Thread sourceThread) {
        failureReporter.execute(sourceThread, request.completionHandler());
    }

    public int activeCount() {
        synchronized (lock) {
            return active;
        }
    }

    public int pendingCount() {
        synchronized (lock) {
            return pending.size();
        }
    }

    public int outstandingCount() {
        synchronized (lock) {
            return outstanding;
        }
    }

    @FunctionalInterface
    public interface ThreadStarter {

        /** Starts {@code task} asynchronously and returns the thread that owns that exact invocation. */
        Thread start(String threadPrefix, Runnable task);
    }

    @FunctionalInterface
    interface PermitReleaser {

        void release(Permit permit);
    }

    public static final class Reservation {

        private final BoundedCloseDispatcher owner;
        private final ArrayDeque<Permit> permits;

        private Reservation(BoundedCloseDispatcher owner, int permitCount) {
            this.owner = owner;
            permits = new ArrayDeque<>(permitCount);
            for (int index = 0; index < permitCount; index++) {
                permits.addLast(new Permit(owner));
            }
        }

        public Permit takePermit() {
            synchronized (this) {
                Permit permit = permits.pollFirst();
                if (permit == null) {
                    throw new IllegalStateException("Stream close reservation has no unused permits");
                }
                return permit;
            }
        }

        public void dispatch(CloseRequest request) {
            Objects.requireNonNull(request, "request");
            takePermit().dispatch(request);
        }

        public void dispatch(Closeable closeable, String threadPrefix, Consumer<? super Throwable> failureHandler) {
            dispatch(closeRequest(closeable, threadPrefix, failureHandler));
        }

        public void dispatch(
                Closeable closeable,
                String threadPrefix,
                Consumer<? super Throwable> failureHandler,
                Runnable completionHandler) {
            dispatch(closeRequest(closeable, threadPrefix, failureHandler, completionHandler));
        }

        public void dispatch(CloseRequest first, CloseRequest second) {
            Objects.requireNonNull(first, "first");
            Permit firstPermit;
            Permit secondPermit = null;
            synchronized (this) {
                firstPermit = permits.pollFirst();
                if (firstPermit == null) {
                    throw new IllegalStateException("Stream close reservation has no unused permits");
                }
                if (second != null) {
                    secondPermit = permits.pollFirst();
                    if (secondPermit == null) {
                        permits.addFirst(firstPermit);
                        throw new IllegalStateException("Stream close reservation has too few unused permits");
                    }
                }
            }
            if (secondPermit == null) {
                firstPermit.dispatch(first);
            } else {
                firstPermit.dispatchPair(first, secondPermit, second);
            }
        }

        public void release() {
            release(RELEASE_PERMIT);
        }

        void release(PermitReleaser permitReleaser) {
            Objects.requireNonNull(permitReleaser, "permitReleaser");
            Throwable firstFailure = null;
            synchronized (this) {
                Permit permit;
                while ((permit = permits.pollFirst()) != null) {
                    try {
                        permitReleaser.release(permit);
                    } catch (RuntimeException | Error releaseFailure) {
                        if (firstFailure == null) {
                            firstFailure = releaseFailure;
                        }
                    }
                }
            }
            rethrowReleaseFailure(firstFailure);
        }
    }

    public static final class Permit {

        private final BoundedCloseDispatcher owner;
        private final AtomicBoolean consumed = new AtomicBoolean();

        private Permit(BoundedCloseDispatcher owner) {
            this.owner = owner;
        }

        public void dispatch(CloseRequest request) {
            dispatchOutcome(request).rethrowStartFailure();
        }

        public void dispatchPair(CloseRequest first, Permit secondPermit, CloseRequest second) {
            owner.dispatchReservedPair(this, first, secondPermit, second).rethrowStartFailure();
        }

        DispatchOutcome dispatchOutcome(CloseRequest request) {
            Objects.requireNonNull(request, "request");
            return owner.dispatchReserved(this, request);
        }

        public void closeInline(Closeable closeable) throws IOException {
            Objects.requireNonNull(closeable, "closeable");
            if (!consumed.compareAndSet(false, true)) {
                throw new IllegalStateException("Stream close permit has already been consumed");
            }
            try {
                closeable.close();
            } finally {
                owner.releaseReserved(1);
            }
        }

        public void release() {
            if (consumed.compareAndSet(false, true)) {
                owner.releaseReserved(1);
            }
        }
    }

    private static void rethrowReleaseFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    /**
     * Separates pre-admission exceptions from failures to start an already accepted physical close.
     *
     * <p>Returning this value means that one of the verified worker, the FIFO queue, or the prestarted fallback owner
     * owns the physical close. A recorded starter failure may still be rethrown to preserve the synchronous API, but it
     * must not be interpreted as a transfer of cleanup responsibility back to the caller.
     */
    static final class DispatchOutcome {

        private final Throwable startFailure;

        private DispatchOutcome(Throwable startFailure) {
            this.startFailure = startFailure;
        }

        private static DispatchOutcome accepted(Throwable startFailure) {
            return new DispatchOutcome(startFailure);
        }

        void rethrowStartFailure() {
            if (startFailure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (startFailure instanceof Error error) {
                throw error;
            }
        }
    }

    public record CloseRequest(
            Closeable closeable,
            String threadPrefix,
            Consumer<? super Throwable> settlement,
            Consumer<? super Throwable> failureHandler,
            Runnable completionHandler) {

        public CloseRequest {
            Objects.requireNonNull(closeable, "closeable");
            Objects.requireNonNull(threadPrefix, "threadPrefix");
            Objects.requireNonNull(settlement, "settlement");
            Objects.requireNonNull(failureHandler, "failureHandler");
            Objects.requireNonNull(completionHandler, "completionHandler");
        }
    }

    private final class CloseExecution {

        private final CloseRequest request;
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final AtomicReference<Throwable> startFailure = new AtomicReference<>();

        private CloseExecution(CloseRequest request) {
            this.request = request;
        }

        private CloseRequest request() {
            return request;
        }

        private void recordStartFailure(Throwable failure) {
            if (!startFailure.compareAndSet(null, failure)) {
                throw new IllegalStateException("Close execution already has a starter failure");
            }
        }

        private Throwable startFailure() {
            return startFailure.get();
        }

        private void run() {
            if (!claimed.compareAndSet(false, true)) {
                return;
            }
            completeExecution(this, attemptPhysicalClose(request.closeable()));
        }

        private boolean claimAfterStartFailure() {
            return claimed.compareAndSet(false, true);
        }

        private void runClaimedAfterStartFailure() {
            Throwable failure = startFailure.get();
            if (failure == null) {
                throw new IllegalStateException("Fallback close execution has no starter failure");
            }
            SuppressionSupport.attach(failure, attemptPhysicalClose(request.closeable()));
            completeExecution(this, failure);
        }
    }

    private final class WorkerStartGate {

        private final CloseExecution execution;
        private final Thread dispatchThread;
        private boolean decided;
        private boolean accepted;
        private boolean inlineInvocation;
        private Thread acceptedOwner;

        private WorkerStartGate(CloseExecution execution, Thread dispatchThread) {
            this.execution = execution;
            this.dispatchThread = dispatchThread;
        }

        private void accept(Thread owner) {
            synchronized (this) {
                if (inlineInvocation || owner == dispatchThread || !owner.isAlive()) {
                    throw new RejectedExecutionException(
                            "Close thread starter must start the task asynchronously on the returned live thread");
                }
                acceptedOwner = owner;
                accepted = true;
                decided = true;
                notifyAll();
            }
        }

        private void reject() {
            synchronized (this) {
                if (decided) {
                    return;
                }
                decided = true;
                notifyAll();
            }
        }

        private void run() {
            Thread worker = Thread.currentThread();
            if (worker == dispatchThread) {
                synchronized (this) {
                    inlineInvocation = true;
                }
                return;
            }
            boolean restoreInterrupt = false;
            synchronized (this) {
                while (!decided) {
                    try {
                        wait();
                    } catch (InterruptedException interruption) {
                        restoreInterrupt = true;
                    }
                }
                if (!accepted) {
                    if (restoreInterrupt) {
                        worker.interrupt();
                    }
                    return;
                }
                if (worker != acceptedOwner) {
                    if (restoreInterrupt) {
                        worker.interrupt();
                    }
                    rejectMismatchedOwner(worker);
                    return;
                }
            }
            try {
                execution.run();
            } finally {
                if (restoreInterrupt) {
                    worker.interrupt();
                }
            }
        }

        private void rejectMismatchedOwner(Thread actualOwner) {
            RejectedExecutionException ownershipFailure = new RejectedExecutionException(
                    "Close task ran on " + actualOwner.getName() + " instead of the thread returned by its starter");
            execution.recordStartFailure(ownershipFailure);
            if (!handoffAfterStartFailure(execution)) {
                publishFailure(execution.request(), ownershipFailure, actualOwner);
            }
        }
    }
}
