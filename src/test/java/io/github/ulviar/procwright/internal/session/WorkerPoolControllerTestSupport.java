/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

abstract class WorkerPoolControllerTestSupport {

    static void assertPartition(
            WorkerPoolController<?> pool, int size, int idle, int leased, int starting, int retiring) {
        PoolMetrics.Snapshot metrics = pool.metrics();
        assertEquals(size, metrics.size());
        assertEquals(idle, metrics.idle());
        assertEquals(leased, metrics.leased());
        assertEquals(starting, metrics.starting());
        assertEquals(retiring, metrics.retiring());
        assertEquals(size, idle + leased + starting + retiring);
    }

    static void assertSuppressedExactlyOnce(Throwable primary, Throwable expected) {
        int matches = 0;
        for (Throwable suppressed : primary.getSuppressed()) {
            if (suppressed == expected) {
                matches++;
            }
        }
        assertEquals(1, matches);
    }

    static void awaitAvailableAdmissions(PoolLifecycleDispatcher.AdmissionPool admissions, int expected)
            throws InterruptedException {
        long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (admissions.availablePermits() != expected && System.nanoTime() < deadlineNanos) {
            Thread.sleep(1);
        }
        assertEquals(expected, admissions.availablePermits());
    }

    static WorkerPoolController.RetirementAdmissionProvider immediateAdmissions(
            PoolLifecycleDispatcher.AdmissionPool admissions) {
        return deadlineNanos -> {
            PoolLifecycleDispatcher.Admission admission = admissions.tryAcquire();
            if (admission == null) {
                throw new java.util.concurrent.TimeoutException("test lifecycle capacity exhausted");
            }
            return admission;
        };
    }

    static WorkerPoolController<TestWorker> controller(
            java.util.function.Supplier<TestWorker> factory,
            java.util.function.Consumer<TestWorker> closer,
            Options options) {
        return new WorkerPoolController<>(
                factory, closeAction(closer), options, Failures.INSTANCE, "test worker", "test-");
    }

    static WorkerPoolController<TestWorker> controllerWithAdmissions(
            java.util.function.Supplier<TestWorker> factory,
            java.util.function.Consumer<TestWorker> closer,
            Options options,
            PoolLifecycleDispatcher.AdmissionPool workerAdmissions) {
        return new WorkerPoolController<>(
                factory,
                closeAction(closer),
                options,
                Failures.INSTANCE,
                "separate-admission worker",
                "test-separate-admission-",
                new WorkerPoolController.Dependencies(
                        Runnable::run,
                        (thread, failure) -> {},
                        System::nanoTime,
                        null,
                        immediateAdmissions(workerAdmissions)));
    }

    static WorkerPoolController<TestWorker> inlineController(
            java.util.function.Supplier<TestWorker> factory,
            java.util.function.Consumer<TestWorker> closer,
            Options options) {
        return new WorkerPoolController<>(
                factory, inlineCloseAction(closer), options, Failures.INSTANCE, "test worker", "test-");
    }

    static WorkerPoolController<TestWorker> controller(
            java.util.function.Supplier<TestWorker> factory,
            java.util.function.Consumer<TestWorker> closer,
            Options options,
            java.util.function.Consumer<Runnable> replenishmentStarter,
            java.util.function.BiConsumer<Thread, Throwable> lateFailureReporter,
            java.util.function.LongSupplier clock,
            PoolReplenisher.Waiter backoffWaiter) {
        return new WorkerPoolController<>(
                factory,
                closeAction(closer),
                options,
                Failures.INSTANCE,
                "test worker",
                "test-",
                new WorkerPoolController.Dependencies(
                        replenishmentStarter,
                        lateFailureReporter,
                        clock,
                        backoffWaiter,
                        PoolLifecycleDispatcher::admit));
    }

    static WorkerPoolController<TestWorker> controller(
            java.util.function.Supplier<TestWorker> factory,
            java.util.function.Consumer<TestWorker> closer,
            Options options,
            java.util.function.Consumer<Runnable> replenishmentStarter) {
        return new WorkerPoolController<>(
                factory,
                closeAction(closer),
                options,
                Failures.INSTANCE,
                "test worker",
                "test-",
                new WorkerPoolController.Dependencies(
                        replenishmentStarter,
                        (thread, failure) -> {},
                        System::nanoTime,
                        null,
                        PoolLifecycleDispatcher::admit));
    }

    static WorkerRetirement.Action<TestWorker> closeAction(java.util.function.Consumer<TestWorker> closer) {
        return (worker, admission) -> WorkerCloseSupport.closeOutcome(
                () -> closer.accept(worker),
                CompletableFuture.completedFuture(null),
                CompletableFuture.completedFuture(null),
                admission);
    }

    private static WorkerRetirement.Action<TestWorker> inlineCloseAction(
            java.util.function.Consumer<TestWorker> closer) {
        return (worker, admission) -> {
            try {
                closer.accept(worker);
                return CompletableFuture.completedFuture(WorkerRetirement.Outcome.success());
            } catch (Throwable failure) {
                return CompletableFuture.completedFuture(WorkerRetirement.Outcome.failure(failure));
            }
        };
    }

    static void joinThread(AtomicReference<Thread> reference, String operation) throws InterruptedException {
        Thread thread = reference.get();
        if (thread == null) {
            return;
        }
        thread.join(TimeUnit.SECONDS.toMillis(1));
        assertFalse(thread.isAlive(), operation + " thread did not stop");
    }

    static boolean awaitStackFrame(Thread thread, String className, String methodName, Duration timeout)
            throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadlineNanos) {
            for (StackTraceElement frame : thread.getStackTrace()) {
                if (frame.getClassName().equals(className)
                        && frame.getMethodName().equals(methodName)) {
                    return true;
                }
            }
            Thread.sleep(1);
        }
        return false;
    }

    static void awaitIgnoringInterrupt(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    static <T> T throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException exception) {
            throw exception;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError("unsupported test failure", failure);
    }

    record TestWorker(int id) {}

    static CompletableFuture<Void> publicCloseView(WorkerPoolController<?> pool) {
        return PoolCloseSupport.asyncView(pool.closeAsync(), PublicCloseFailures.INSTANCE);
    }

    enum PublicCloseFailures implements PoolCloseSupport.FailureFactory {
        INSTANCE;

        @Override
        public RuntimeException drainTimeout(Duration timeout) {
            return new IllegalStateException("unexpected drain timeout: " + timeout);
        }

        @Override
        public RuntimeException interrupted(InterruptedException cause) {
            return new IllegalStateException("unexpected close interruption", cause);
        }

        @Override
        public RuntimeException workerFailed(Throwable cause) {
            return new IllegalStateException("unexpected worker close failure", cause);
        }
    }

    static final class CloseAwareWorker implements AutoCloseable {

        final CompletableFuture<Void> terminal = CompletableFuture.completedFuture(null);
        final CompletableFuture<Void> physicalCleanup = new CompletableFuture<>();
        final CountDownLatch physicalCloseFinished = new CountDownLatch(1);
        final AtomicBoolean physicallyClosed = new AtomicBoolean();
        final AtomicInteger physicalCloseCalls = new AtomicInteger();

        void failPhysicalClose(Throwable failure) {
            if (physicallyClosed.compareAndSet(false, true)) {
                physicalCloseCalls.incrementAndGet();
                physicalCleanup.completeExceptionally(failure);
                physicalCloseFinished.countDown();
            }
        }

        @Override
        public void close() {
            if (physicallyClosed.compareAndSet(false, true)) {
                physicalCloseCalls.incrementAndGet();
                physicalCleanup.complete(null);
                physicalCloseFinished.countDown();
            }
        }
    }

    static final class ExitCallbackWorker implements AutoCloseable {

        final CompletableFuture<Void> exit = new CompletableFuture<>();
        final CompletableFuture<Void> physicalCleanup = CompletableFuture.completedFuture(null);
        final AtomicInteger closeCalls = new AtomicInteger();

        CompletableFuture<Void> onExit() {
            return exit;
        }

        CompletableFuture<Void> physicalCleanup() {
            return physicalCleanup;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            exit.complete(null);
        }
    }

    record Options(
            int maxSize,
            int warmupSize,
            int minIdle,
            Duration acquireTimeout,
            int maxRequestsPerWorker,
            Duration maxWorkerAge,
            boolean backgroundReplenishment)
            implements WorkerPoolPolicy.Options {}

    enum FailureKind {
        CLOSED,
        ACQUIRE_TIMEOUT,
        INTERRUPTED,
        STARTUP_FAILED,
        RETIREMENT_FAILED
    }

    @SuppressWarnings("serial")
    static final class PoolFailure extends RuntimeException {

        final FailureKind kind;

        PoolFailure(FailureKind kind, String message, Throwable cause) {
            super(message, cause);
            this.kind = kind;
        }
    }

    enum Failures implements WorkerPoolController.FailureFactory {
        INSTANCE;

        @Override
        public RuntimeException closed(String message) {
            return new PoolFailure(FailureKind.CLOSED, message, null);
        }

        @Override
        public RuntimeException acquireTimeout(String message) {
            return new PoolFailure(FailureKind.ACQUIRE_TIMEOUT, message, null);
        }

        @Override
        public RuntimeException acquireInterrupted(String message, InterruptedException cause) {
            return new PoolFailure(FailureKind.INTERRUPTED, message, cause);
        }

        @Override
        public RuntimeException startupFailed(String message, Throwable cause) {
            return new PoolFailure(FailureKind.STARTUP_FAILED, message, cause);
        }

        @Override
        public RuntimeException retirementFailed(String message, Throwable cause) {
            return new PoolFailure(FailureKind.RETIREMENT_FAILED, message, cause);
        }

        @Override
        public Throwable exposeAggregate(RuntimeException primary, Throwable aggregate) {
            if (primary instanceof PoolFailure failure) {
                return new PoolFailure(failure.kind, failure.getMessage(), aggregate);
            }
            return aggregate;
        }
    }
}
