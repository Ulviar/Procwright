/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

final class WorkerStartupTest {

    @ParameterizedTest
    @EnumSource(WorkerStartup.StopReason.class)
    void cancelledReadinessDoesNotProduceALateFailureReport(WorkerStartup.StopReason reason) throws Exception {
        CountDownLatch probeEntered = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        CountDownLatch probeStopped = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        AtomicReference<WorkerStartup.LateCompletion<String>> late = new AtomicReference<>();
        WorkerStartup<String> startup = new WorkerStartup<>(
                () -> {
                    ReadinessSupport.check(
                            "worker",
                            worker -> {
                                probeEntered.countDown();
                                try {
                                    awaitIgnoringInterrupt(releaseProbe);
                                } finally {
                                    probeStopped.countDown();
                                }
                            },
                            Duration.ofHours(1),
                            closes::incrementAndGet);
                    return "worker";
                },
                "cancelled-readiness-test-",
                late::set);
        try {
            startup.start();
            assertTrue(probeEntered.await(1, TimeUnit.SECONDS));
            stop(startup, reason);

            WorkerStartup.LateCompletion<String> completion = awaitLate(late);
            assertNotNull(completion);
            assertNull(completion.session());
            assertInstanceOf(CommandExecutionException.class, completion.failure());
            assertNull(completion.failureTarget(), "an owned readiness interruption is expected cancellation");
            assertEquals(1, closes.get(), "cancellation must still close the worker");
            assertStopped(reason, startup.await(deadline()));
        } finally {
            startup.signalClosed();
            releaseProbe.countDown();
            assertTrue(probeStopped.await(1, TimeUnit.SECONDS));
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cancelledReadinessStillReportsCleanupFailures(boolean fatal) throws Exception {
        CountDownLatch probeEntered = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        CountDownLatch probeStopped = new CountDownLatch(1);
        Throwable closeFailure =
                fatal ? new AssertionError("cleanup failed") : new IllegalStateException("cleanup failed");
        AtomicReference<WorkerStartup.LateCompletion<String>> late = new AtomicReference<>();
        WorkerStartup<String> startup = new WorkerStartup<>(
                () -> {
                    ReadinessSupport.check(
                            "worker",
                            worker -> {
                                probeEntered.countDown();
                                try {
                                    awaitIgnoringInterrupt(releaseProbe);
                                } finally {
                                    probeStopped.countDown();
                                }
                            },
                            Duration.ofHours(1),
                            () -> {
                                if (closeFailure instanceof Error error) {
                                    throw error;
                                }
                                throw (RuntimeException) closeFailure;
                            });
                    return "worker";
                },
                "cancelled-readiness-cleanup-test-",
                late::set);
        try {
            startup.start();
            assertTrue(probeEntered.await(1, TimeUnit.SECONDS));
            startup.signalClosed();

            WorkerStartup.LateCompletion<String> completion = awaitLate(late);
            assertNotNull(completion);
            assertNotNull(completion.failureTarget());
            assertSame(closeFailure, completion.failure().getSuppressed()[0]);
        } finally {
            startup.signalClosed();
            releaseProbe.countDown();
            assertTrue(probeStopped.await(1, TimeUnit.SECONDS));
        }
    }

    private static void stop(WorkerStartup<?> startup, WorkerStartup.StopReason reason) {
        switch (reason) {
            case CLOSED -> assertStopped(reason, startup.signalClosed());
            case TIMED_OUT -> assertStopped(reason, startup.signalTimeout());
            case INTERRUPTED -> {
                Thread.currentThread().interrupt();
                try {
                    assertStopped(reason, startup.await(deadline()));
                    assertTrue(Thread.currentThread().isInterrupted());
                } finally {
                    Thread.interrupted();
                }
            }
        }
    }

    @Test
    void factoryCompletionOwnsTheResultWhenItWins() throws Exception {
        AtomicReference<WorkerStartup.LateCompletion<String>> late = new AtomicReference<>();
        WorkerStartup<String> startup = new WorkerStartup<>(() -> "worker", "startup-test-", late::set);

        startup.start();

        assertEquals(
                "worker",
                assertInstanceOf(WorkerStartup.CreatedWorker.class, startup.await(deadline()))
                        .session());
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
        startup.start();
        assertTrue(factoryStarted.await(1, TimeUnit.SECONDS));

        assertStopped(WorkerStartup.StopReason.TIMED_OUT, startup.await(System.nanoTime()));
        releaseFactory.countDown();

        WorkerStartup.LateCompletion<String> completion = awaitLate(late);
        assertEquals("late-worker", completion.session());
        assertEquals(PooledWorkerRetireReason.STARTUP_TIMEOUT, completion.reason());
        assertStopped(WorkerStartup.StopReason.TIMED_OUT, startup.await(deadline()));
    }

    @Test
    void closeSignalCannotReplaceAnEarlierFactorySignal() throws Exception {
        WorkerStartup<String> startup =
                new WorkerStartup<>(() -> "worker", "startup-factory-winner-test-", ignored -> {});
        startup.start();
        assertInstanceOf(WorkerStartup.CreatedWorker.class, startup.await(deadline()));

        assertInstanceOf(WorkerStartup.CreatedWorker.class, startup.signalClosed());

        assertEquals(
                "worker",
                assertInstanceOf(WorkerStartup.CreatedWorker.class, startup.await(deadline()))
                        .session());
    }

    @Test
    void closeBeforeThreadStartPreventsFactoryExecution() throws Exception {
        AtomicInteger factoryCalls = new AtomicInteger();
        CountDownLatch startEntered = new CountDownLatch(1);
        CountDownLatch releaseStart = new CountDownLatch(1);
        AtomicReference<Thread> workerThread = new AtomicReference<>();
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
        Thread launcher = new Thread(startup::start);
        launcher.start();
        assertTrue(startEntered.await(1, TimeUnit.SECONDS));

        assertStopped(WorkerStartup.StopReason.CLOSED, startup.signalClosed());
        releaseStart.countDown();
        launcher.join(1_000);
        workerThread.get().join(1_000);

        assertEquals(0, factoryCalls.get());
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
        startup.start();
        assertTrue(factoryEntered.await(1, TimeUnit.SECONDS));

        assertThrows(IllegalStateException.class, startup::start);

        releaseFactory.countDown();
        assertEquals(
                "worker",
                assertInstanceOf(WorkerStartup.CreatedWorker.class, startup.await(deadline()))
                        .session());
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
        Thread launcher = new Thread(startup::start);
        launcher.start();
        assertTrue(startEntered.await(1, TimeUnit.SECONDS));

        assertStopped(WorkerStartup.StopReason.TIMED_OUT, startup.signalTimeout());
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
        startup.start();

        WorkerStartup.Failed<?> observed = assertInstanceOf(WorkerStartup.Failed.class, startup.await(deadline()));

        assertSame(expected, observed.failure());
    }

    @Test
    void failureTargetCaptureCannotReplaceFactoryFailureOrPreventSettlement() throws Exception {
        IllegalStateException expected = new IllegalStateException("factory failed");
        AssertionError captureFailure = new AssertionError("context loader unavailable");
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
        startup.start();

        WorkerStartup.Failed<?> observed = assertInstanceOf(WorkerStartup.Failed.class, startup.await(deadline()));

        assertSame(expected, observed.failure());
    }

    @Test
    void factoryWinnerSurvivesCallerInterruptionAndRestoresInterruptStatus() throws Exception {
        AtomicReference<String> result = new AtomicReference<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        WorkerStartup<String> startup =
                new WorkerStartup<>(() -> "worker", "startup-factory-interrupt-test-", ignored -> {});
        startup.start();
        assertInstanceOf(WorkerStartup.CreatedWorker.class, startup.await(deadline()));
        Thread waiter = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                result.set((String) assertInstanceOf(WorkerStartup.CreatedWorker.class, startup.await(deadline()))
                        .session());
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
    }

    @Test
    void interruptionWinnerOwnsLateWorkerRetirement() throws Exception {
        CountDownLatch factoryStarted = new CountDownLatch(1);
        CountDownLatch releaseFactory = new CountDownLatch(1);
        CountDownLatch waiterStarted = new CountDownLatch(1);
        AtomicReference<WorkerStartup.Outcome<String>> outcome = new AtomicReference<>();
        AtomicReference<WorkerStartup.LateCompletion<String>> late = new AtomicReference<>();
        WorkerStartup<String> startup = new WorkerStartup<>(
                () -> {
                    factoryStarted.countDown();
                    awaitIgnoringInterrupt(releaseFactory);
                    return "late-worker";
                },
                "startup-interrupt-winner-test-",
                late::set);
        startup.start();
        Thread waiter = new Thread(() -> {
            waiterStarted.countDown();
            outcome.set(startup.await(deadline()));
        });
        waiter.start();
        assertTrue(factoryStarted.await(1, TimeUnit.SECONDS));
        assertTrue(waiterStarted.await(1, TimeUnit.SECONDS));

        waiter.interrupt();
        waiter.join(TimeUnit.SECONDS.toMillis(1));
        releaseFactory.countDown();

        assertStopped(WorkerStartup.StopReason.INTERRUPTED, outcome.get());
        WorkerStartup.LateCompletion<String> completion = awaitLate(late);
        assertEquals("late-worker", completion.session());
        assertEquals(PooledWorkerRetireReason.STARTUP_INTERRUPTED, completion.reason());
        assertStopped(WorkerStartup.StopReason.INTERRUPTED, startup.await(deadline()));
    }

    @Test
    void factoryErrorWinnerSurvivesCallerInterruption() throws Exception {
        AssertionError expected = new AssertionError("factory failed");
        AtomicReference<WorkerStartup.Outcome<String>> outcome = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        WorkerStartup<String> startup = new WorkerStartup<>(
                () -> {
                    throw expected;
                },
                "startup-error-interrupt-test-",
                ignored -> {});
        startup.start();
        assertInstanceOf(WorkerStartup.Failed.class, startup.await(deadline()));
        Thread waiter = new Thread(() -> {
            Thread.currentThread().interrupt();
            try {
                outcome.set(startup.await(deadline()));
            } finally {
                interrupted.set(Thread.currentThread().isInterrupted());
            }
        });
        waiter.start();
        waiter.join(TimeUnit.SECONDS.toMillis(1));

        assertSame(
                expected,
                assertInstanceOf(WorkerStartup.Failed.class, outcome.get()).failure());
        assertTrue(interrupted.get());
    }

    @Test
    void selectedCloseAndTimeoutSurviveLaterCallerInterruption() throws Exception {
        for (WorkerStartup.StopReason selected :
                new WorkerStartup.StopReason[] {WorkerStartup.StopReason.CLOSED, WorkerStartup.StopReason.TIMED_OUT}) {
            WorkerStartup<String> startup = new WorkerStartup<>(() -> "unused", "selected-startup-", ignored -> {});
            if (selected == WorkerStartup.StopReason.CLOSED) {
                startup.signalClosed();
            } else {
                startup.signalTimeout();
            }
            AtomicReference<WorkerStartup.Outcome<String>> observed = new AtomicReference<>();
            AtomicBoolean interrupted = new AtomicBoolean();
            Thread waiter = new Thread(() -> {
                Thread.currentThread().interrupt();
                observed.set(startup.await(deadline()));
                interrupted.set(Thread.currentThread().isInterrupted());
            });
            waiter.start();
            waiter.join(1_000);

            assertStopped(selected, observed.get());
            assertTrue(interrupted.get());
            assertSame(observed.get(), startup.await(deadline()), "a second wait must return the same winner");
        }
    }

    private static long deadline() {
        return System.nanoTime() + Duration.ofSeconds(1).toNanos();
    }

    private static void assertStopped(WorkerStartup.StopReason reason, WorkerStartup.Outcome<?> outcome) {
        assertEquals(
                reason, assertInstanceOf(WorkerStartup.Stopped.class, outcome).reason());
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
