/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class BoundedTaskRunnerTest {

    @Test
    void isolatedLimiterEnforcesConfiguredCapacityAndRestoresEveryReservation() {
        BoundedTaskLimiter limiter = new BoundedTaskLimiter(2);
        BoundedTaskPermit first = limiter.tryAcquire();
        BoundedTaskPermit second = limiter.tryAcquire();

        assertNotNull(first);
        assertNotNull(second);
        assertNull(limiter.tryAcquire());
        try {
            first.close();
            assertEquals(1, limiter.availablePermits());
            try (BoundedTaskPermit replacement = limiter.tryAcquire()) {
                assertNotNull(replacement);
                assertEquals(0, limiter.availablePermits());
            }
            assertEquals(1, limiter.availablePermits());
        } finally {
            first.close();
            second.close();
        }
        assertEquals(2, limiter.availablePermits());
    }

    @Test
    void arbitraryCallbacksUseAdaptiveNonInheritingDaemonOwners() throws Exception {
        int taskCount = 64;
        BoundedTaskLimiter limiter = new BoundedTaskLimiter(1);
        ThreadLocal<String> contamination = new ThreadLocal<>();
        InheritableThreadLocal<String> inherited = new InheritableThreadLocal<>();
        inherited.set("caller-state");
        Set<Thread> threads = new HashSet<>();
        try {
            for (int index = 0; index < taskCount; index++) {
                Thread callbackThread = BoundedTaskRunner.run(
                        limiter, "procwright-adaptive-callback-test-", deadline(Duration.ofSeconds(1)), () -> {
                            assertNull(contamination.get(), "callback ThreadLocal state crossed invocation ownership");
                            assertNull(inherited.get(), "callback inherited caller ThreadLocal state");
                            assertTrue(Thread.currentThread().isDaemon());
                            assertTrue(Thread.currentThread()
                                    .getName()
                                    .startsWith("procwright-adaptive-callback-test-"));
                            if (virtualThreadsAvailable()) {
                                assertTrue(isVirtual(Thread.currentThread()), "Java 21+ should use a virtual owner");
                            }
                            contamination.set("must-die-with-thread");
                            return Thread.currentThread();
                        });
                assertTrue(threads.add(callbackThread), "a callback owner was reused across invocations");
            }
        } finally {
            inherited.remove();
        }
        assertEquals(taskCount, threads.size());
        assertEquals(1, limiter.availablePermits());
    }

    @Test
    void freshOwnerRecoversAfterThreadFactoryRejectionWithoutLeakingAdmission() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        SecurityException rejection = new SecurityException("owner start denied");
        BoundedTaskLimiter limiter = new BoundedTaskLimiter(1);
        BoundedTaskHandoff handoff = new BoundedTaskHandoff();

        ExecutionException failure = assertThrows(
                ExecutionException.class,
                () -> BoundedTaskTestSupport.runTracked(
                        limiter,
                        "procwright-rejected-owner-task-",
                        deadline(Duration.ofSeconds(1)),
                        handoff,
                        (threadPrefix, task) -> {
                            attempts.incrementAndGet();
                            throw rejection;
                        },
                        () -> "unreachable"));

        assertSame(rejection, failure.getCause());
        assertEquals(BoundedTaskHandoff.Phase.REJECTED_BEFORE_ADMISSION, handoff.phase());
        assertEquals(1, attempts.get());
        assertEquals(1, limiter.availablePermits());
        assertEquals(
                "recovered",
                BoundedTaskRunner.run(
                        limiter,
                        "procwright-recovered-owner-task-",
                        deadline(Duration.ofSeconds(1)),
                        () -> "recovered"));
        assertEquals(1, limiter.availablePermits());
    }

    @Test
    void timeoutInterruptsRunningTask() throws Exception {
        BoundedTaskLimiter limiter = new BoundedTaskLimiter(1);
        CountDownLatch waitForInterrupt = new CountDownLatch(1);
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        long operationDeadline = TimeUnit.SECONDS.toNanos(30);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        try {
            Future<Throwable> attempt = executor.submit(() -> captureFailure(() -> BoundedTaskTestSupport.runTracked(
                    limiter,
                    "procwright-bounded-task-test-",
                    operationDeadline,
                    new BoundedTaskHandoff(),
                    (threadPrefix, task) -> {
                        Thread thread = new Thread(task, threadPrefix + "timeout-interrupt");
                        thread.setDaemon(true);
                        return thread;
                    },
                    new DeadlineAfterTaskStartsClock(operationDeadline, taskStarted),
                    () -> {
                        taskStarted.countDown();
                        try {
                            waitForInterrupt.await();
                        } catch (InterruptedException exception) {
                            interrupted.countDown();
                            throw exception;
                        }
                        return null;
                    })));
            assertInstanceOf(TimeoutException.class, attempt.get(1, TimeUnit.SECONDS));
        } finally {
            waitForInterrupt.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }

        assertTrue(interrupted.await(1, TimeUnit.SECONDS));
        try (BoundedTaskPermit permit = limiter.acquire(deadline(Duration.ofSeconds(1)))) {
            assertNotNull(permit);
            assertEquals(0, limiter.availablePermits());
        }
        assertEquals(1, limiter.availablePermits());
    }

    @Test
    void nonCooperativeTimedOutTaskRetainsCapacityUntilItActuallyStops() throws Exception {
        BoundedTaskLimiter limiter = new BoundedTaskLimiter(1);
        int baselineCapacity = limiter.availablePermits();
        long operationDeadline = TimeUnit.SECONDS.toNanos(30);
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch stopped = new CountDownLatch(1);
        AtomicBoolean secondStarted = new AtomicBoolean();

        try {
            assertThrows(
                    TimeoutException.class,
                    () -> BoundedTaskTestSupport.runTracked(
                            limiter,
                            "procwright-bounded-task-test-",
                            operationDeadline,
                            new BoundedTaskHandoff(),
                            (threadPrefix, task) -> {
                                Thread thread = new Thread(task, threadPrefix + "non-cooperative");
                                thread.setDaemon(true);
                                return thread;
                            },
                            new DeadlineAfterAdmissionClock(operationDeadline),
                            () -> {
                                started.countDown();
                                try {
                                    awaitIgnoringInterrupts(release);
                                } finally {
                                    stopped.countDown();
                                }
                                return null;
                            }));
            assertTrue(started.await(1, TimeUnit.SECONDS));

            assertThrows(
                    TimeoutException.class,
                    () -> BoundedTaskTestSupport.runTracked(
                            limiter,
                            "procwright-bounded-task-test-",
                            operationDeadline,
                            new BoundedTaskHandoff(),
                            (threadPrefix, task) -> {
                                Thread thread = new Thread(task, threadPrefix + "must-not-start");
                                thread.setDaemon(true);
                                return thread;
                            },
                            () -> operationDeadline,
                            () -> {
                                secondStarted.set(true);
                                return null;
                            }));
            assertFalse(secondStarted.get());
            assertEquals(0, limiter.availablePermits(), "the live callback must retain the only permit");
        } finally {
            release.countDown();
        }
        assertTrue(stopped.await(1, TimeUnit.SECONDS));
        assertEquals(
                "released",
                BoundedTaskRunner.run(
                        limiter, "procwright-bounded-task-test-", deadline(Duration.ofSeconds(1)), () -> "released"));
        assertEquals(baselineCapacity, limiter.availablePermits());
    }

    @Test
    void cancellationWakesCallerAndRetainsPermitUntilNonCooperativeTaskStops() throws Exception {
        BoundedTaskLimiter limiter = new BoundedTaskLimiter(1);
        BoundedTaskRunner.CancellationSignal cancellation = new BoundedTaskRunner.CancellationSignal();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> running = executor.submit(() -> BoundedTaskRunner.run(
                    limiter, "procwright-cancelled-task-test-", deadline(Duration.ofHours(1)), cancellation, () -> {
                        started.countDown();
                        awaitIgnoringInterrupts(release);
                        return null;
                    }));
            assertTrue(started.await(1, TimeUnit.SECONDS));

            cancellation.cancel();

            ExecutionException wrapper = assertThrows(ExecutionException.class, () -> running.get(1, TimeUnit.SECONDS));
            assertInstanceOf(BoundedTaskRunner.TaskCancelledException.class, wrapper.getCause());
            assertEquals(0, limiter.availablePermits());

            release.countDown();
            try (BoundedTaskPermit permit = limiter.acquire(deadline(Duration.ofSeconds(1)))) {
                assertNotNull(permit);
                assertEquals(0, limiter.availablePermits());
            }
            assertEquals(1, limiter.availablePermits());
        } finally {
            release.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void callerInterruptionSelectsAbandonmentBeforeInterruptingActiveTask() throws Exception {
        BoundedTaskLimiter limiter = new BoundedTaskLimiter(1);
        CountDownLatch taskStarted = new CountDownLatch(1);
        CountDownLatch blockTask = new CountDownLatch(1);
        CountDownLatch taskObservedInterrupt = new CountDownLatch(1);
        AtomicReference<Thread> callerThread = new AtomicReference<>();
        AtomicReference<Throwable> abandonmentFailure = new AtomicReference<>();
        AtomicBoolean selectionObservedByTask = new AtomicBoolean();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> running = executor.submit(() -> {
                callerThread.set(Thread.currentThread());
                return captureFailure(() -> BoundedTaskRunner.runWithAbandonment(
                        limiter,
                        "procwright-interrupted-task-test-",
                        deadline(Duration.ofHours(1)),
                        new BoundedTaskRunner.CancellationSignal(),
                        failure -> assertTrue(abandonmentFailure.compareAndSet(null, failure)),
                        () -> {
                            taskStarted.countDown();
                            try {
                                blockTask.await();
                                return null;
                            } catch (InterruptedException interruption) {
                                selectionObservedByTask.set(abandonmentFailure.get() != null);
                                taskObservedInterrupt.countDown();
                                throw interruption;
                            }
                        }));
            });
            assertTrue(taskStarted.await(1, TimeUnit.SECONDS));

            callerThread.get().interrupt();
            Throwable observed = running.get(1, TimeUnit.SECONDS);

            assertInstanceOf(InterruptedException.class, observed);
            assertSame(observed, abandonmentFailure.get());
            assertTrue(taskObservedInterrupt.await(1, TimeUnit.SECONDS));
            assertTrue(selectionObservedByTask.get());
        } finally {
            blockTask.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void cancellationWhileWaitingForCapacityDoesNotStartTask() throws Exception {
        BoundedTaskLimiter limiter = new BoundedTaskLimiter(1);
        BoundedTaskPermit occupied = limiter.acquire(deadline(Duration.ofSeconds(1)));
        BoundedTaskRunner.CancellationSignal cancellation = new BoundedTaskRunner.CancellationSignal();
        AtomicBoolean started = new AtomicBoolean();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<?> waiting = executor.submit(() -> BoundedTaskRunner.run(
                    limiter,
                    "procwright-cancelled-admission-test-",
                    deadline(Duration.ofHours(1)),
                    cancellation,
                    () -> {
                        started.set(true);
                        return null;
                    }));

            cancellation.cancel();

            ExecutionException wrapper = assertThrows(ExecutionException.class, () -> waiting.get(1, TimeUnit.SECONDS));
            assertInstanceOf(BoundedTaskRunner.TaskCancelledException.class, wrapper.getCause());
            assertEquals(false, started.get());
        } finally {
            occupied.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void completedTasksDoNotAccumulateCancellationListeners() throws Exception {
        BoundedTaskLimiter limiter = new BoundedTaskLimiter(1);
        BoundedTaskRunner.CancellationSignal cancellation = new BoundedTaskRunner.CancellationSignal();

        for (int index = 0; index < 100; index++) {
            int expected = index;
            assertEquals(
                    expected,
                    BoundedTaskRunner.run(
                            limiter,
                            "procwright-cancellation-listener-test-",
                            deadline(Duration.ofSeconds(1)),
                            cancellation,
                            () -> expected));
            assertEquals(0, cancellation.listenerCountForTest());
        }
    }

    @Test
    void threadStartFailuresAfterWrapperStartCannotReleaseALateTaskOrPermitTwice() throws Exception {
        for (Throwable rejection : List.of(
                new SecurityException("thread start denied"), new AssertionError("fatal thread start failure"))) {
            BoundedTaskLimiter limiter = new BoundedTaskLimiter(1);
            BoundedTaskHandoff handoff = new BoundedTaskHandoff();
            AtomicBoolean taskStarted = new AtomicBoolean();
            AtomicReference<Thread> rejectedThread = new AtomicReference<>();

            Throwable observed = captureFailure(() -> BoundedTaskTestSupport.runTracked(
                    limiter,
                    "procwright-rejected-start-test-",
                    deadline(Duration.ofSeconds(10)),
                    handoff,
                    (threadPrefix, task) -> {
                        Thread thread = new Thread(task, threadPrefix + "0") {
                            @Override
                            public synchronized void start() {
                                super.start();
                                if (rejection instanceof RuntimeException runtimeException) {
                                    throw runtimeException;
                                }
                                throw (Error) rejection;
                            }
                        };
                        thread.setDaemon(true);
                        rejectedThread.set(thread);
                        return thread;
                    },
                    () -> {
                        taskStarted.set(true);
                        return null;
                    }));

            if (rejection instanceof RuntimeException) {
                assertInstanceOf(ExecutionException.class, observed);
                assertSame(rejection, observed.getCause());
            } else {
                assertSame(rejection, observed);
            }
            assertEquals(BoundedTaskHandoff.Phase.REJECTED_BEFORE_ADMISSION, handoff.phase());
            assertTrue(handoff.retrySafe());
            rejectedThread.get().join(TimeUnit.SECONDS.toMillis(1));
            assertFalse(rejectedThread.get().isAlive());
            assertFalse(taskStarted.get(), "a rejected start must not release the task through the admission gate");
            assertEquals(1, limiter.availablePermits(), "start rejection leaked or double-released the permit");
        }
    }

    @Test
    void fatalThreadCreationFailureCannotLeakPermitOrHandoffOwnership() {
        BoundedTaskLimiter limiter = new BoundedTaskLimiter(1);
        BoundedTaskHandoff handoff = new BoundedTaskHandoff();
        AssertionError injected = new AssertionError("thread creation failed");
        AtomicBoolean taskStarted = new AtomicBoolean();

        AssertionError failure = assertThrows(
                AssertionError.class,
                () -> BoundedTaskTestSupport.runTracked(
                        limiter,
                        "procwright-fatal-thread-creation-test-",
                        deadline(Duration.ofSeconds(10)),
                        handoff,
                        (threadPrefix, task) -> {
                            throw injected;
                        },
                        () -> {
                            taskStarted.set(true);
                            return null;
                        }));

        assertSame(injected, failure);
        assertEquals(BoundedTaskHandoff.Phase.REJECTED_BEFORE_ADMISSION, handoff.phase());
        assertTrue(handoff.retrySafe());
        assertFalse(taskStarted.get());
        assertEquals(1, limiter.availablePermits());
    }

    @Test
    void admissionDeadlineInterleavingsAreSelectedByThePostAcquisitionDeadlineCheck() throws Exception {
        assertAdmissionDeadlineDecision(false);
        assertAdmissionDeadlineDecision(true);
    }

    private static void assertAdmissionDeadlineDecision(boolean deadlineElapsed) throws Exception {
        long operationDeadline = TimeUnit.SECONDS.toNanos(30);
        BoundedTaskLimiter limiter = new BoundedTaskLimiter(1);
        BoundedTaskPermit occupied = limiter.acquire(deadline(Duration.ofSeconds(1)));
        BoundedTaskHandoff handoff = new BoundedTaskHandoff();
        ControlledNanoClock clock = new ControlledNanoClock();
        AtomicBoolean taskStarted = new AtomicBoolean();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> attempt = executor.submit(() -> captureFailure(() -> BoundedTaskTestSupport.runTracked(
                    limiter,
                    "procwright-admission-deadline-test-",
                    operationDeadline,
                    handoff,
                    (threadPrefix, task) -> {
                        Thread thread = new Thread(task, threadPrefix + "0");
                        thread.setDaemon(true);
                        return thread;
                    },
                    clock,
                    () -> {
                        taskStarted.set(true);
                        return null;
                    })));

            assertTrue(clock.awaitAdmissionCheck(), "caller did not begin bounded admission");
            occupied.close();
            assertTrue(clock.awaitPostAcquisitionCheck(), "caller did not reach the post-acquisition deadline check");
            clock.completePostAcquisitionCheck(deadlineElapsed ? operationDeadline : 1L);

            Throwable observed = attempt.get(1, TimeUnit.SECONDS);
            if (deadlineElapsed) {
                assertInstanceOf(TimeoutException.class, observed);
                assertEquals(BoundedTaskHandoff.Phase.REJECTED_BEFORE_ADMISSION, handoff.phase());
                assertTrue(handoff.retrySafe());
                assertFalse(taskStarted.get(), "an expired pre-start deadline released a late task");
            } else {
                assertNull(observed);
                assertEquals(BoundedTaskHandoff.Phase.ADMITTED, handoff.phase());
                assertFalse(handoff.retrySafe());
                assertTrue(taskStarted.get());
            }
            assertEquals(1, limiter.availablePermits(), "deadline arbitration leaked or double-released the permit");
        } finally {
            occupied.close();
            clock.completePostAcquisitionCheck(operationDeadline);
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private static long deadline(Duration duration) {
        return System.nanoTime() + duration.toNanos();
    }

    private static boolean virtualThreadsAvailable() {
        try {
            Thread.class.getMethod("ofVirtual");
            return true;
        } catch (NoSuchMethodException unavailable) {
            return false;
        }
    }

    private static boolean isVirtual(Thread thread) {
        try {
            return (boolean) Thread.class.getMethod("isVirtual").invoke(thread);
        } catch (ReflectiveOperationException failure) {
            throw new AssertionError("Could not inspect callback thread kind", failure);
        }
    }

    private static Throwable captureFailure(ThrowingOperation operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    @FunctionalInterface
    private interface ThrowingOperation {

        void run() throws Throwable;
    }

    private static final class ControlledNanoClock implements java.util.function.LongSupplier {

        private final AtomicInteger calls = new AtomicInteger();
        private final AtomicLong now = new AtomicLong();
        private final CountDownLatch admissionCheck = new CountDownLatch(1);
        private final CountDownLatch postAcquisitionCheck = new CountDownLatch(1);
        private final CountDownLatch releasePostAcquisitionCheck = new CountDownLatch(1);

        @Override
        public long getAsLong() {
            int invocation = calls.incrementAndGet();
            if (invocation == 1) {
                admissionCheck.countDown();
                return 0L;
            }
            if (invocation == 2) {
                postAcquisitionCheck.countDown();
                awaitIgnoringInterrupts(releasePostAcquisitionCheck);
            }
            return now.get();
        }

        private boolean awaitAdmissionCheck() throws InterruptedException {
            return admissionCheck.await(1, TimeUnit.SECONDS);
        }

        private boolean awaitPostAcquisitionCheck() throws InterruptedException {
            return postAcquisitionCheck.await(1, TimeUnit.SECONDS);
        }

        private void completePostAcquisitionCheck(long value) {
            now.set(value);
            releasePostAcquisitionCheck.countDown();
        }
    }

    private static final class DeadlineAfterAdmissionClock implements java.util.function.LongSupplier {

        private final long deadline;
        private final AtomicInteger reads = new AtomicInteger();

        private DeadlineAfterAdmissionClock(long deadline) {
            this.deadline = deadline;
        }

        @Override
        public long getAsLong() {
            return reads.incrementAndGet() < 3 ? 0L : deadline;
        }
    }

    private static final class DeadlineAfterTaskStartsClock implements java.util.function.LongSupplier {

        private final long deadline;
        private final CountDownLatch taskStarted;
        private final AtomicInteger reads = new AtomicInteger();

        private DeadlineAfterTaskStartsClock(long deadline, CountDownLatch taskStarted) {
            this.deadline = deadline;
            this.taskStarted = taskStarted;
        }

        @Override
        public long getAsLong() {
            if (reads.incrementAndGet() < 3) {
                return 0L;
            }
            awaitIgnoringInterrupts(taskStarted);
            return deadline;
        }
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
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
