/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import io.github.ulviar.procwright.internal.Threading;
import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.session.PooledSessionMetrics;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;

abstract class WorkerPoolControllerTestSupport {

    static void assertPartition(
            WorkerPoolController<?> pool, int size, int idle, int leased, int starting, int retiring) {
        PooledSessionMetrics metrics = pool.metrics();
        assertEquals(size, metrics.size());
        assertEquals(idle, metrics.idle());
        assertEquals(leased, metrics.leased());
        assertEquals(starting, metrics.starting());
        assertEquals(retiring, metrics.retiring());
        assertEquals(size, idle + leased + starting + retiring);
    }

    static boolean awaitMetrics(
            WorkerPoolController<?> pool, Predicate<PooledSessionMetrics> condition, Duration timeout)
            throws InterruptedException {
        long deadlineNanos = System.nanoTime() + timeout.toNanos();
        do {
            if (condition.test(pool.metrics())) {
                return true;
            }
            TimeUnit.MILLISECONDS.sleep(1);
        } while (System.nanoTime() < deadlineNanos);
        return condition.test(pool.metrics());
    }

    static WorkerPoolController<TestWorker> controller(
            java.util.function.Supplier<TestWorker> factory,
            java.util.function.Consumer<TestWorker> closer,
            WorkerPoolSettings<?> settings) {
        return WorkerPoolController.fromSettings(
                factory, closeAction(closer), settings, Failures.INSTANCE, "test worker", "test-", System::nanoTime);
    }

    static WorkerPoolController<TestWorker> inlineController(
            java.util.function.Supplier<TestWorker> factory,
            java.util.function.Consumer<TestWorker> closer,
            WorkerPoolSettings<?> settings) {
        return WorkerPoolController.fromSettings(
                factory,
                inlineCloseAction(closer),
                settings,
                Failures.INSTANCE,
                "test worker",
                "test-",
                System::nanoTime);
    }

    static WorkerPoolController<TestWorker> controller(
            java.util.function.Supplier<TestWorker> factory,
            java.util.function.Consumer<TestWorker> closer,
            WorkerPoolSettings<?> settings,
            PoolReplenisher.Scheduler replenishmentScheduler,
            java.util.function.Consumer<Throwable> lateFailureReporter,
            java.util.function.LongSupplier clock) {
        return WorkerPoolController.fromSettings(
                factory,
                closeAction(closer),
                settings,
                Failures.INSTANCE,
                "test worker",
                "test-",
                new WorkerPoolController.Dependencies(
                        replenishmentScheduler, reportingSink(lateFailureReporter), clock));
    }

    static java.util.function.Consumer<FailureReport> reportingSink(java.util.function.Consumer<Throwable> observer) {
        return report -> BoundedFailureReporter.shared()
                .execute(Thread.currentThread(), () -> observer.accept(report.failure()));
    }

    static WorkerPoolController<TestWorker> controller(
            java.util.function.Supplier<TestWorker> factory,
            java.util.function.Consumer<TestWorker> closer,
            WorkerPoolSettings<?> settings,
            PoolReplenisher.Scheduler replenishmentScheduler) {
        return WorkerPoolController.fromSettings(
                factory,
                closeAction(closer),
                settings,
                Failures.INSTANCE,
                "test worker",
                "test-",
                new WorkerPoolController.Dependencies(replenishmentScheduler, report -> {}, System::nanoTime));
    }

    static PoolReplenisher.Scheduler threadedScheduler(String threadPrefix) {
        return (task, delay) -> {
            Threading.start(threadPrefix, task);
            return PoolReplenisher.Cancellation.NONE;
        };
    }

    static PoolReplenisher.Scheduler inlineScheduler() {
        return (task, delay) -> {
            task.run();
            return PoolReplenisher.Cancellation.NONE;
        };
    }

    static WorkerRetirement.Action<TestWorker> closeAction(java.util.function.Consumer<TestWorker> closer) {
        return worker ->
                WorkerCloseSupport.closeOutcome(() -> closer.accept(worker), CompletableFuture.completedFuture(null));
    }

    static WorkerRetirement.Action<TestWorker> inlineCloseAction(java.util.function.Consumer<TestWorker> closer) {
        return worker -> {
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

    record TestWorker(int id) {}

    static WorkerPoolSettings<Object> settings(
            int maxSize,
            int warmupSize,
            int minIdle,
            Duration acquireTimeout,
            int maxRequestsPerWorker,
            Duration maxWorkerAge) {
        return WorkerPoolSettings.defaults()
                .withMaxSize(maxSize)
                .withWarmupSize(warmupSize)
                .withMinIdle(minIdle)
                .withAcquireTimeout(acquireTimeout)
                .withCloseTimeout(acquireTimeout)
                .withMaxRequestsPerWorker(maxRequestsPerWorker)
                .withMaxWorkerAge(maxWorkerAge);
    }

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
