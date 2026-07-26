/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class WorkerStartupTest {

    @Test
    void factoryCompletionOwnsTheResultWhenItWins() throws Exception {
        AtomicReference<WorkerStartup.LateCompletion<String>> late = new AtomicReference<>();
        WorkerStartup<String> startup = new WorkerStartup<>(() -> "worker", "startup-test-", late::set);

        startup.start(permit());

        assertEquals("worker", startup.await(deadline()).session());
        assertEquals(WorkerStartup.TerminalDecision.FACTORY_COMPLETED, startup.terminalDecision());
        assertNull(late.get());
    }

    @Test
    void timeoutOwnsTheCallerOutcomeAndLateWorkerRetirement() throws Exception {
        CountDownLatch factoryStarted = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        AtomicReference<WorkerStartup.LateCompletion<String>> late = new AtomicReference<>();
        WorkerStartup<String> startup = new WorkerStartup<>(
                () -> {
                    factoryStarted.countDown();
                    awaitIgnoringInterrupt(releaseFactory);
                    return "late-worker";
                },
                "startup-timeout-test-",
                late::set);
        startup.start(permit());
        assertTrue(factoryStarted.await(1, TimeUnit.SECONDS));

        assertThrows(TimeoutException.class, () -> startup.await(System.nanoTime()));
        releaseFactory.countDown();

        WorkerStartup.LateCompletion<String> completion = awaitLate(late);
        assertEquals("late-worker", completion.session());
        assertEquals(PooledWorkerRetireReason.STARTUP_TIMEOUT, completion.reason());
        assertEquals(WorkerStartup.TerminalDecision.TIMED_OUT, startup.terminalDecision());
    }

    @Test
    void closeSignalCannotReplaceAnEarlierFactorySignal() throws Exception {
        WorkerStartup<String> startup =
                new WorkerStartup<>(() -> "worker", "startup-factory-winner-test-", ignored -> {});
        startup.start(permit());
        awaitDecision(startup, WorkerStartup.TerminalDecision.FACTORY_COMPLETED);

        assertEquals(WorkerStartup.TerminalDecision.FACTORY_COMPLETED, startup.signalClosed());

        assertEquals("worker", startup.await(deadline()).session());
    }

    @Test
    void closeBeforeThreadStartPreventsFactoryExecutionAndReleasesPermit() throws Exception {
        AtomicInteger factoryCalls = new AtomicInteger();
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        AtomicReference<Thread> workerThread = new AtomicReference<>();
        BoundedTaskLimiter limiter = new BoundedTaskLimiter(1);
        WorkerStartup<String> startup = new WorkerStartup<>(
                () -> {
                    factoryCalls.incrementAndGet();
                    return "worker";
                },
                "startup-close-before-start-test-",
                ignored -> {},
                (name, task) -> {
                    Thread thread = new Thread(task, name) {
                        @Override
                        public synchronized void start() {
                            startEntered.countDown();
                            awaitIgnoringInterrupt(releaseStart);
                            super.start();
                        }
                    };
                    workerThread.set(thread);
                    return thread;
                });
        BoundedTaskPermit initialPermit = limiter.tryAcquire();
        assertNotNull(initialPermit);
        Thread launcher = new Thread(() -> startup.start(initialPermit));
        launcher.start();
        assertTrue(startEntered.await(1, TimeUnit.SECONDS));

        assertEquals(WorkerStartup.TerminalDecision.CLOSED, startup.signalClosed());
        releaseStart.countDown();
        launcher.join(1_000);
        workerThread.get().join(1_000);

        assertEquals(0, factoryCalls.get());
        BoundedTaskPermit recoveredPermit = limiter.tryAcquire();
        assertNotNull(recoveredPermit, "startup permit was not released");
        recoveredPermit.close();
    }

    @Test
    void startupCanBeLaunchedOnlyOnce() throws Exception {
        AtomicInteger factoryCalls = new AtomicInteger();
        CountDownLatch factoryEntered = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        WorkerStartup<String> startup = new WorkerStartup<>(
                () -> {
                    factoryCalls.incrementAndGet();
                    factoryEntered.countDown();
                    awaitIgnoringInterrupt(releaseFactory);
                    return "worker";
                },
                "startup-once-test-",
                ignored -> {});
        BoundedTaskLimiter secondLimiter = new BoundedTaskLimiter(1);
        startup.start(permit());
        assertTrue(factoryEntered.await(1, TimeUnit.SECONDS));
        BoundedTaskPermit secondPermit = secondLimiter.tryAcquire();
        assertNotNull(secondPermit);

        assertThrows(IllegalStateException.class, () -> startup.start(secondPermit));

        BoundedTaskPermit recovered = secondLimiter.tryAcquire();
        assertNotNull(recovered, "rejected second launch did not release its permit");
        recovered.close();
        releaseFactory.countDown();
        assertEquals("worker", startup.await(deadline()).session());
        assertEquals(1, factoryCalls.get());
    }

    @Test
    void timeoutAfterThreadLaunchButBeforeFactoryEntryPublishesLateCompletion() throws Exception {
        AtomicInteger factoryCalls = new AtomicInteger();
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        AtomicReference<Thread> workerThread = new AtomicReference<>();
        AtomicReference<WorkerStartup.LateCompletion<String>> late = new AtomicReference<>();
        WorkerStartup<String> startup = new WorkerStartup<>(
                () -> {
                    factoryCalls.incrementAndGet();
                    return "worker";
                },
                "startup-timeout-before-entry-test-",
                late::set,
                (name, task) -> {
                    Thread thread = new Thread(task, name) {
                        @Override
                        public synchronized void start() {
                            startEntered.countDown();
                            awaitIgnoringInterrupt(releaseStart);
                            super.start();
                        }
                    };
                    workerThread.set(thread);
                    return thread;
                });
        Thread launcher = new Thread(() -> startup.start(permit()));
        launcher.start();
        assertTrue(startEntered.await(1, TimeUnit.SECONDS));

        assertEquals(WorkerStartup.TerminalDecision.TIMED_OUT, startup.signalTimeout());
        releaseStart.countDown();
        launcher.join(1_000);
        workerThread.get().join(1_000);

        WorkerStartup.LateCompletion<String> completion = awaitLate(late);
        assertNull(completion.session());
        assertNull(completion.failure());
        assertEquals(PooledWorkerRetireReason.STARTUP_TIMEOUT, completion.reason());
        assertEquals(0, factoryCalls.get());
    }

    @Test
    void factoryFailureIsReturnedWithoutCreatingALateWorker() throws Exception {
        IllegalStateException expected = new IllegalStateException("factory failed");
        WorkerStartup<String> startup = new WorkerStartup<>(
                () -> {
                    throw expected;
                },
                "startup-failure-test-",
                ignored -> {});
        startup.start(permit());

        ExecutionException observed = assertThrows(ExecutionException.class, () -> startup.await(deadline()));

        assertSame(expected, observed.getCause());
        assertEquals(WorkerStartup.TerminalDecision.FACTORY_COMPLETED, startup.terminalDecision());
    }

    @Test
    void failureTargetCaptureCannotReplaceFactoryFailureOrPreventSettlement() throws Exception {
        IllegalStateException expected = new IllegalStateException("factory failed");
        AssertionError captureFailure = new AssertionError("context loader unavailable");
        BoundedTaskLimiter limiter = new BoundedTaskLimiter(1);
        WorkerStartup<String> startup = new WorkerStartup<>(
                () -> {
                    throw expected;
                },
                "startup-hostile-target-test-",
                ignored -> {},
                (name, task) -> new Thread(task, name) {
                    @Override
                    public ClassLoader getContextClassLoader() {
                        throw captureFailure;
                    }
                });
        BoundedTaskPermit initialPermit = limiter.tryAcquire();
        assertNotNull(initialPermit);
        startup.start(initialPermit);

        ExecutionException observed = assertThrows(ExecutionException.class, () -> startup.await(deadline()));

        assertSame(expected, observed.getCause());
        assertEquals(WorkerStartup.TerminalDecision.FACTORY_COMPLETED, startup.terminalDecision());
        BoundedTaskPermit recoveredPermit = limiter.tryAcquire();
        assertNotNull(recoveredPermit, "factory permit was not released");
        recoveredPermit.close();
    }

    @Test
    void factoryWinnerSurvivesCallerInterruptionAndRestoresInterruptStatus() throws Exception {
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        WorkerStartup<String> startup =
                new WorkerStartup<>(() -> "worker", "startup-factory-interrupt-test-", ignored -> {});
        startup.start(permit());
        awaitDecision(startup, WorkerStartup.TerminalDecision.FACTORY_COMPLETED);
        Thread waiter = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                result.set(startup.await(deadline()).session());
            } catch (Throwable observed) {
                failure.set(observed);
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        waiter.start();
        waiter.join(TimeUnit.SECONDS.toMillis(1));

        assertEquals("worker", result.get());
        assertNull(failure.get());
        assertTrue(interrupted.get());
        assertEquals(WorkerStartup.TerminalDecision.FACTORY_COMPLETED, startup.terminalDecision());
    }

    @Test
    void interruptionWinnerOwnsLateWorkerRetirement() throws Exception {
        CountDownLatch factoryStarted = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        CountDownLatch waiterStarted = new CountDownLatch(1);
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicReference<WorkerStartup.LateCompletion<String>> late = new AtomicReference<>();
        WorkerStartup<String> startup = new WorkerStartup<>(
                () -> {
                    factoryStarted.countDown();
                    awaitIgnoringInterrupt(releaseFactory);
                    return "late-worker";
                },
                "startup-interrupt-winner-test-",
                late::set);
        startup.start(permit());
        Thread waiter = new Thread(() -> {
            waiterStarted.countDown();
            try {
                startup.await(deadline());
            } catch (Throwable observed) {
                failure.set(observed);
            }
        });
        waiter.start();
        assertTrue(factoryStarted.await(1, TimeUnit.SECONDS));
        assertTrue(waiterStarted.await(1, TimeUnit.SECONDS));

        waiter.interrupt();
        waiter.join(TimeUnit.SECONDS.toMillis(1));
        releaseFactory.countDown();

        assertTrue(failure.get() instanceof InterruptedException);
        WorkerStartup.LateCompletion<String> completion = awaitLate(late);
        assertEquals("late-worker", completion.session());
        assertEquals(PooledWorkerRetireReason.STARTUP_INTERRUPTED, completion.reason());
        assertEquals(WorkerStartup.TerminalDecision.INTERRUPTED, startup.terminalDecision());
    }

    @Test
    void factoryErrorWinnerSurvivesCallerInterruption() throws Exception {
        AssertionError expected = new AssertionError("factory failed");
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        WorkerStartup<String> startup = new WorkerStartup<>(
                () -> {
                    throw expected;
                },
                "startup-error-interrupt-test-",
                ignored -> {});
        startup.start(permit());
        awaitDecision(startup, WorkerStartup.TerminalDecision.FACTORY_COMPLETED);
        Thread waiter = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                startup.await(deadline());
            } catch (Throwable observed) {
                failure.set(observed);
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        waiter.start();
        waiter.join(TimeUnit.SECONDS.toMillis(1));

        assertTrue(failure.get() instanceof ExecutionException);
        assertSame(expected, failure.get().getCause());
        assertTrue(interrupted.get());
        assertEquals(WorkerStartup.TerminalDecision.FACTORY_COMPLETED, startup.terminalDecision());
    }

    private static BoundedTaskPermit permit() {
        BoundedTaskPermit permit = new BoundedTaskLimiter(1).tryAcquire();
        if (permit == null) {
            throw new AssertionError("test permit was not available");
        }
        return permit;
    }

    private static long deadline() {
        return System.nanoTime() + Duration.ofSeconds(1).toNanos();
    }

    private static void awaitDecision(WorkerStartup<?> startup, WorkerStartup.TerminalDecision expected)
            throws InterruptedException {
        long deadlineNanos = deadline();
        while (startup.terminalDecision() != expected && System.nanoTime() < deadlineNanos) {
            Thread.sleep(1);
        }
        assertEquals(expected, startup.terminalDecision());
    }

    private static <S> WorkerStartup.LateCompletion<S> awaitLate(
            AtomicReference<WorkerStartup.LateCompletion<S>> completion) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (completion.get() == null && System.nanoTime() < deadline) {
            Thread.sleep(1);
        }
        return completion.get();
    }

    private static void awaitIgnoringInterrupt(CountDownLatch latch) {
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
}
