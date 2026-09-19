/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class TimedTaskRunnerTest {

    @Test
    void returnsTaskValueAndExposesTaskFailure() throws Exception {
        assertEquals("value", TimedTaskRunner.run("timed-task-value-", deadline(), () -> "value"));

        IllegalStateException failure = new IllegalStateException("failed");
        ExecutionException observed = assertThrows(
                ExecutionException.class,
                () -> TimedTaskRunner.run("timed-task-failure-", deadline(), () -> {
                    throw failure;
                }));

        assertSame(failure, observed.getCause());
    }

    @Test
    void timedOutNonCooperativeTaskDoesNotBlockIndependentTask() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch finished = new CountDownLatch(1);
        AtomicBoolean interrupted = new AtomicBoolean();

        assertThrows(
                TimeoutException.class,
                () -> TimedTaskRunner.run(
                        "timed-task-timeout-",
                        System.nanoTime() + Duration.ofMillis(40).toNanos(),
                        () -> {
                            entered.countDown();
                            try {
                                awaitIgnoringInterrupt(release);
                            } finally {
                                interrupted.set(Thread.currentThread().isInterrupted());
                                finished.countDown();
                            }
                            return null;
                        }));

        assertTrue(entered.await(1, TimeUnit.SECONDS));
        try {
            assertEquals(
                    "independent", TimedTaskRunner.run("timed-task-independent-", deadline(), () -> "independent"));
        } finally {
            release.countDown();
        }
        assertTrue(finished.await(1, TimeUnit.SECONDS));
        assertTrue(interrupted.get());
    }

    @Test
    void cancellationSelectsTerminalStateBeforeInterruptingTask() throws Exception {
        TimedTaskRunner.CancellationSignal cancellation = new TimedTaskRunner.CancellationSignal();
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        AtomicBoolean terminalSelected = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                TimedTaskRunner.runCancellable(
                        "timed-task-cancel-", deadline(), cancellation, ignored -> terminalSelected.set(true), () -> {
                            entered.countDown();
                            try {
                                while (true) {
                                    Thread.sleep(1_000);
                                }
                            } catch (InterruptedException expected) {
                                if (terminalSelected.get()) {
                                    interrupted.countDown();
                                }
                                throw expected;
                            }
                        });
            } catch (Throwable observed) {
                failure.set(observed);
            }
        });

        caller.start();
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        cancellation.cancel();
        caller.join(1_000);

        assertFalse(caller.isAlive());
        assertInstanceOf(TimedTaskRunner.TaskCancelledException.class, failure.get());
        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
    }

    @Test
    void preCancelledSignalDoesNotStartTask() {
        TimedTaskRunner.CancellationSignal cancellation = new TimedTaskRunner.CancellationSignal();
        AtomicBoolean started = new AtomicBoolean();
        cancellation.cancel();

        assertThrows(
                TimedTaskRunner.TaskCancelledException.class,
                () -> TimedTaskRunner.runCancellable(
                        "timed-task-pre-cancelled-", deadline(), cancellation, ignored -> {}, () -> {
                            started.set(true);
                            return null;
                        }));

        assertFalse(started.get());
    }

    @Test
    void cancellationSignalRejectsOverlappingCallbacksWithoutLosingTheFirst() {
        TimedTaskRunner.CancellationSignal cancellation = new TimedTaskRunner.CancellationSignal();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        var registration = cancellation.register(first::incrementAndGet);
        try (registration) {
            assertThrows(IllegalStateException.class, () -> cancellation.register(second::incrementAndGet));
            assertTrue(cancellation.cancel());
            assertFalse(cancellation.cancel());
        }

        assertEquals(1, first.get());
        assertEquals(0, second.get());
    }

    @Test
    void closingOldRegistrationCannotRemoveItsReplacement() {
        TimedTaskRunner.CancellationSignal cancellation = new TimedTaskRunner.CancellationSignal();
        AtomicInteger first = new AtomicInteger();
        AtomicInteger second = new AtomicInteger();
        var old = cancellation.register(first::incrementAndGet);
        old.close();
        var replacement = cancellation.register(second::incrementAndGet);
        try (replacement) {
            old.close();
            cancellation.cancel();
        }

        assertEquals(0, first.get());
        assertEquals(1, second.get());
    }

    @Test
    void registrationAfterCancellationIsDeliveredOnce() {
        TimedTaskRunner.CancellationSignal cancellation = new TimedTaskRunner.CancellationSignal();
        AtomicInteger calls = new AtomicInteger();
        cancellation.cancel();

        var registration = cancellation.register(calls::incrementAndGet);
        try (registration) {
            assertEquals(1, calls.get());
            assertFalse(cancellation.cancel());
        }

        assertEquals(1, calls.get());
    }

    @Test
    void staleRegistrationCannotRemoveTheSameListenerRegisteredAgain() {
        TimedTaskRunner.CancellationSignal cancellation = new TimedTaskRunner.CancellationSignal();
        AtomicInteger calls = new AtomicInteger();
        Runnable listener = calls::incrementAndGet;
        var old = cancellation.register(listener);
        old.close();
        var replacement = cancellation.register(listener);
        try (replacement) {
            old.close();
            cancellation.cancel();
        }

        assertEquals(1, calls.get());
    }

    @Test
    void completedCallbackUnregistersBeforeTheNextCallback() throws Exception {
        TimedTaskRunner.CancellationSignal cancellation = new TimedTaskRunner.CancellationSignal();
        for (int i = 0; i < 2; i++) {
            assertEquals(
                    "completed",
                    TimedTaskRunner.runCancellable(
                            "timed-task-sequential-", deadline(), cancellation, ignored -> {}, () -> "completed"));
        }
        assertTrue(cancellation.cancel());
        assertThrows(
                TimedTaskRunner.TaskCancelledException.class,
                () -> TimedTaskRunner.runCancellable(
                        "timed-task-after-cancel-", deadline(), cancellation, ignored -> {}, () -> "unexpected"));
    }

    @Test
    void abandonmentWinsBeforeTaskClaimsCallbackEntry() {
        TimedTaskRunner.TaskControl control = new TimedTaskRunner.TaskControl();

        control.abandon();

        assertFalse(control.begin(deadline()));
    }

    @Test
    void callerInterruptionRunsAbandonmentAndInterruptsTask() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch taskInterrupted = new CountDownLatch(1);
        AtomicBoolean abandonmentObserved = new AtomicBoolean();
        AtomicBoolean callerInterruptRestored = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                TimedTaskRunner.runCancellable(
                        "timed-task-caller-interrupt-",
                        deadline(),
                        new TimedTaskRunner.CancellationSignal(),
                        ignored -> abandonmentObserved.set(true),
                        () -> {
                            entered.countDown();
                            try {
                                Thread.sleep(10_000);
                            } catch (InterruptedException expected) {
                                taskInterrupted.countDown();
                                throw expected;
                            }
                            return null;
                        });
            } catch (InterruptedException observed) {
                Thread.currentThread().interrupt();
                failure.set(observed);
            } catch (Throwable observed) {
                failure.set(observed);
            } finally {
                callerInterruptRestored.set(Thread.currentThread().isInterrupted());
            }
        });

        caller.start();
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        caller.interrupt();
        caller.join(1_000);

        assertInstanceOf(InterruptedException.class, failure.get());
        assertTrue(abandonmentObserved.get());
        assertTrue(taskInterrupted.await(1, TimeUnit.SECONDS));
        assertTrue(callerInterruptRestored.get());
    }

    @Test
    void trackedStartDistinguishesPreStartDeadlineFromStartedTask() throws Exception {
        TaskStart expired = new TaskStart();
        assertThrows(
                TimeoutException.class,
                () -> TimedTaskRunner.runTracked("timed-task-expired-", System.nanoTime(), expired, () -> null));
        assertFalse(expired.started());

        TaskStart started = new TaskStart();
        IllegalStateException taskFailure = new IllegalStateException("task failed");
        ExecutionException observed = assertThrows(
                ExecutionException.class,
                () -> TimedTaskRunner.runTracked("timed-task-started-", deadline(), started, () -> {
                    throw taskFailure;
                }));
        assertTrue(started.started());
        assertSame(taskFailure, observed.getCause());
    }

    @Test
    void taskThreadDoesNotInheritCallerThreadLocalState() throws Exception {
        InheritableThreadLocal<String> inherited = new InheritableThreadLocal<>();
        AtomicReference<String> observed = new AtomicReference<>();
        inherited.set("caller");
        try {
            TimedTaskRunner.run("timed-task-thread-state-", deadline(), () -> {
                observed.set(inherited.get());
                return null;
            });
            org.junit.jupiter.api.Assertions.assertNull(observed.get());
        } finally {
            inherited.remove();
        }
    }

    private static long deadline() {
        return System.nanoTime() + Duration.ofSeconds(2).toNanos();
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
