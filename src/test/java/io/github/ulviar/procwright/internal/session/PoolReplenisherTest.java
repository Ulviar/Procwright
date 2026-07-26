/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.Threading;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class PoolReplenisherTest {

    @Test
    void repeatedEnsureCallsScheduleOnlyOneAttempt() {
        AtomicBoolean needed = new AtomicBoolean(true);
        QueuedScheduler scheduler = new QueuedScheduler();
        PoolReplenisher replenisher = replenisher(scheduler, needed::get, () -> {
            needed.set(false);
            return PoolReplenisher.Step.STOP;
        });

        replenisher.ensureStarted();
        replenisher.ensureStarted();

        assertEquals(1, scheduler.size());
        scheduler.runNext();
        replenisher.ensureStarted();
        assertEquals(0, scheduler.size());
    }

    @Test
    void retryBackoffResetsAfterSuccess() {
        AtomicBoolean needed = new AtomicBoolean(true);
        ArrayDeque<PoolReplenisher.Step> steps = new ArrayDeque<>(List.of(
                PoolReplenisher.Step.RETRY,
                PoolReplenisher.Step.SUCCESS,
                PoolReplenisher.Step.RETRY,
                PoolReplenisher.Step.STOP));
        QueuedScheduler scheduler = new QueuedScheduler();
        PoolReplenisher replenisher = replenisher(scheduler, needed::get, () -> {
            PoolReplenisher.Step result = steps.removeFirst();
            if (result == PoolReplenisher.Step.STOP) {
                needed.set(false);
            }
            return result;
        });

        replenisher.ensureStarted();
        scheduler.runAll();

        assertEquals(
                List.of(Duration.ZERO, Duration.ofMillis(10), Duration.ZERO, Duration.ofMillis(10)),
                scheduler.delays());
        assertEquals(1, scheduler.maximumSize());
    }

    @Test
    void stopRecheckCannotLoseNewDemand() {
        AtomicBoolean needed = new AtomicBoolean(true);
        AtomicInteger steps = new AtomicInteger();
        QueuedScheduler scheduler = new QueuedScheduler();
        PoolReplenisher replenisher = replenisher(scheduler, needed::get, () -> {
            if (steps.incrementAndGet() == 2) {
                needed.set(false);
            }
            return PoolReplenisher.Step.STOP;
        });

        replenisher.ensureStarted();
        scheduler.runAll();

        assertEquals(2, steps.get());
        assertEquals(2, scheduler.delays().size());
    }

    @Test
    void retryAttemptYieldsToAnotherPoolAlreadyWaiting() {
        QueuedScheduler scheduler = new QueuedScheduler();
        AtomicBoolean firstNeeded = new AtomicBoolean(true);
        AtomicBoolean secondNeeded = new AtomicBoolean(true);
        AtomicInteger firstSteps = new AtomicInteger();
        AtomicInteger firstStepsSeenBySecond = new AtomicInteger();
        PoolReplenisher first = replenisher(scheduler, firstNeeded::get, () -> {
            int attempt = firstSteps.incrementAndGet();
            if (attempt == 3) {
                firstNeeded.set(false);
                return PoolReplenisher.Step.STOP;
            }
            return PoolReplenisher.Step.RETRY;
        });
        PoolReplenisher second = replenisher(scheduler, secondNeeded::get, () -> {
            firstStepsSeenBySecond.set(firstSteps.get());
            secondNeeded.set(false);
            return PoolReplenisher.Step.STOP;
        });

        first.ensureStarted();
        second.ensureStarted();
        scheduler.runNext();
        scheduler.runNext();
        scheduler.runAll();

        assertEquals(1, firstStepsSeenBySecond.get());
        assertEquals(3, firstSteps.get());
    }

    @Test
    void schedulingFailureIsFatalInsteadOfCreatingFallbackWork() {
        IllegalStateException schedulingFailure = new IllegalStateException("scheduler unavailable");
        AtomicInteger steps = new AtomicInteger();
        AtomicReference<Throwable> observedFailure = new AtomicReference<>();
        PoolReplenisher replenisher = new PoolReplenisher(
                (task, delay) -> {
                    throw schedulingFailure;
                },
                () -> true,
                () -> {
                    steps.incrementAndGet();
                    return PoolReplenisher.Step.STOP;
                },
                observedFailure::set);

        replenisher.ensureStarted();

        assertSame(schedulingFailure, observedFailure.get());
        assertEquals(0, steps.get());
    }

    @Test
    void fatalHandlingKeepsAdmissionClosedUntilPoolStateBecomesTerminal() throws Exception {
        IllegalStateException schedulingFailure = new IllegalStateException("scheduler unavailable");
        AtomicBoolean needed = new AtomicBoolean(true);
        AtomicInteger schedules = new AtomicInteger();
        AtomicReference<Runnable> firstAttempt = new AtomicReference<>();
        CountDownLatch fatalHandlingStarted = new CountDownLatch(1);
        CountDownLatch finishFatalHandling = new CountDownLatch(1);
        PoolReplenisher replenisher = new PoolReplenisher(
                (task, delay) -> {
                    if (schedules.incrementAndGet() == 1) {
                        firstAttempt.set(task);
                        return PoolScheduledAttempt.Cancellation.NONE;
                    }
                    throw schedulingFailure;
                },
                needed::get,
                () -> PoolReplenisher.Step.RETRY,
                failure -> {
                    assertSame(schedulingFailure, failure);
                    fatalHandlingStarted.countDown();
                    await(finishFatalHandling);
                    needed.set(false);
                });

        replenisher.ensureStarted();
        Thread failureThread = Threading.start("test-replenisher-failure-", firstAttempt.get());
        try {
            assertTrue(fatalHandlingStarted.await(1, TimeUnit.SECONDS));

            replenisher.ensureStarted();
            assertEquals(2, schedules.get());
        } finally {
            finishFatalHandling.countDown();
            failureThread.join(TimeUnit.SECONDS.toMillis(1));
        }
        assertFalse(failureThread.isAlive());
    }

    @Test
    void stopRemovesThePendingAttempt() {
        AtomicBoolean needed = new AtomicBoolean(true);
        AtomicInteger steps = new AtomicInteger();
        QueuedScheduler scheduler = new QueuedScheduler();
        PoolReplenisher replenisher = replenisher(scheduler, needed::get, () -> {
            steps.incrementAndGet();
            return PoolReplenisher.Step.STOP;
        });

        replenisher.ensureStarted();
        assertEquals(1, scheduler.size());

        replenisher.stop();
        assertEquals(0, scheduler.size());
        scheduler.runAll();
        assertEquals(0, steps.get());
    }

    private static PoolReplenisher replenisher(
            PoolReplenisher.Scheduler scheduler,
            java.util.function.BooleanSupplier needed,
            java.util.function.Supplier<PoolReplenisher.Step> step) {
        return new PoolReplenisher(scheduler, needed, step, failure -> {});
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        }
    }

    private static final class QueuedScheduler implements PoolReplenisher.Scheduler {

        private final ArrayDeque<Runnable> attempts = new ArrayDeque<>();
        private final ArrayDeque<Duration> delays = new ArrayDeque<>();
        private int maximumSize;

        @Override
        public PoolScheduledAttempt.Cancellation schedule(Runnable task, Duration delay) {
            attempts.addLast(task);
            delays.addLast(delay);
            maximumSize = Math.max(maximumSize, attempts.size());
            return () -> attempts.remove(task);
        }

        private int size() {
            return attempts.size();
        }

        private List<Duration> delays() {
            return List.copyOf(delays);
        }

        private int maximumSize() {
            return maximumSize;
        }

        private void runNext() {
            attempts.removeFirst().run();
        }

        private void runAll() {
            while (!attempts.isEmpty()) {
                runNext();
            }
        }
    }
}
