/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class WorkerHookSupportTest {

    @Test
    void returnsSuccessfulHookValue() {
        String value = WorkerHookSupport.run(
                "procwright-test-hook-",
                Duration.ofSeconds(1),
                () -> "ok",
                () -> new IllegalStateException("timeout"),
                interrupted -> new IllegalStateException("interrupted", interrupted),
                failure -> new IllegalStateException("failure", failure));

        assertEquals("ok", value);
    }

    @Test
    void nonCooperativeHookReturnsAtDeadlineAndRetainsCapacityUntilCompletion() throws InterruptedException {
        CountDownLatch hookStarted = new CountDownLatch(1);
        CountDownLatch releaseHook = new CountDownLatch(1);
        int permitsBefore = BoundedTaskRunner.WORKER_HOOKS.availablePermits();
        long started = System.nanoTime();
        try {
            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> WorkerHookSupport.run(
                            "procwright-test-hook-",
                            Duration.ofMillis(50),
                            () -> {
                                hookStarted.countDown();
                                awaitIgnoringInterrupt(releaseHook);
                                return "late";
                            },
                            () -> new IllegalStateException("timeout"),
                            caught -> new IllegalStateException("interrupted", caught),
                            failure -> new IllegalStateException("failure", failure)));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertEquals("timeout", exception.getMessage());
            assertEquals(0, hookStarted.getCount());
            assertTrue(elapsed.compareTo(Duration.ofMillis(400)) < 0, () -> "hook timeout took " + elapsed);
            assertEquals(permitsBefore - 1, BoundedTaskRunner.WORKER_HOOKS.availablePermits());
        } finally {
            releaseHook.countDown();
        }
        assertTrue(eventuallyTrue(() -> BoundedTaskRunner.WORKER_HOOKS.availablePermits() == permitsBefore));
    }

    @Test
    void mapsCallerInterruptionSeparatelyFromTimeout() {
        try {
            Thread.currentThread().interrupt();

            IllegalStateException exception = assertThrows(
                    IllegalStateException.class,
                    () -> WorkerHookSupport.run(
                            "procwright-test-hook-",
                            Duration.ofSeconds(5),
                            () -> {
                                try {
                                    Thread.sleep(10_000);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                                return null;
                            },
                            () -> new IllegalStateException("timeout"),
                            interrupted -> new IllegalStateException("interrupted", interrupted),
                            failure -> new IllegalStateException("failure", failure)));

            assertInstanceOf(InterruptedException.class, exception.getCause());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void mapsRuntimeFailure() {
        IllegalArgumentException cause = new IllegalArgumentException("bad hook");

        IllegalStateException exception = assertThrows(
                IllegalStateException.class,
                () -> WorkerHookSupport.run(
                        "procwright-test-hook-",
                        Duration.ofSeconds(1),
                        () -> {
                            throw cause;
                        },
                        () -> new IllegalStateException("timeout"),
                        interrupted -> new IllegalStateException("interrupted", interrupted),
                        failure -> new IllegalStateException("failure", failure)));

        assertSame(cause, exception.getCause());
    }

    @Test
    void rethrowsSeriousErrors() {
        AssertionError error = new AssertionError("serious");

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> WorkerHookSupport.run(
                        "procwright-test-hook-",
                        Duration.ofSeconds(1),
                        () -> {
                            throw error;
                        },
                        () -> new IllegalStateException("timeout"),
                        interrupted -> new IllegalStateException("interrupted", interrupted),
                        failure -> new IllegalStateException("failure", failure)));

        assertSame(error, thrown);
    }

    @Test
    void lateRuntimeFailureAfterTimeoutIsReportedExactlyOnce() throws Exception {
        IllegalStateException lateFailure = new IllegalStateException("late hook failure");
        CountDownLatch hookStarted = new CountDownLatch(1);
        CountDownLatch releaseHook = new CountDownLatch(1);
        CountDownLatch reported = new CountDownLatch(1);
        AtomicInteger matchingReports = new AtomicInteger();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            if (failure == lateFailure) {
                matchingReports.incrementAndGet();
                reported.countDown();
            }
        });
        try {
            assertThrows(
                    IllegalStateException.class,
                    () -> WorkerHookSupport.run(
                            "procwright-late-hook-test-",
                            Duration.ofMillis(25),
                            () -> {
                                hookStarted.countDown();
                                awaitIgnoringInterrupt(releaseHook);
                                throw lateFailure;
                            },
                            () -> new IllegalStateException("timeout"),
                            interruption -> new IllegalStateException("interrupted", interruption),
                            failure -> new IllegalStateException("failed", failure)));

            assertTrue(hookStarted.await(1, TimeUnit.SECONDS));
            releaseHook.countDown();
            assertTrue(reported.await(1, TimeUnit.SECONDS));
            assertTrue(eventuallyTrue(() ->
                    BoundedTaskRunner.WORKER_HOOKS.availablePermits() == BoundedTaskRunner.WORKER_HOOKS.capacity()));
            Thread.sleep(50);
            assertEquals(1, matchingReports.get());
        } finally {
            releaseHook.countDown();
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Test
    void lateRuntimeFailureAfterCallerInterruptionIsReportedExactlyOnce() throws Exception {
        IllegalStateException lateFailure = new IllegalStateException("late interrupted hook failure");
        CountDownLatch hookStarted = new CountDownLatch(1);
        CountDownLatch releaseHook = new CountDownLatch(1);
        CountDownLatch callerExited = new CountDownLatch(1);
        CountDownLatch reported = new CountDownLatch(1);
        AtomicInteger matchingReports = new AtomicInteger();
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            if (failure == lateFailure) {
                matchingReports.incrementAndGet();
                reported.countDown();
            }
        });
        Thread caller = new Thread(() -> {
            try {
                WorkerHookSupport.run(
                        "procwright-interrupted-hook-test-",
                        Duration.ofSeconds(5),
                        () -> {
                            hookStarted.countDown();
                            awaitIgnoringInterrupt(releaseHook);
                            throw lateFailure;
                        },
                        () -> new IllegalStateException("timeout"),
                        interruption -> new IllegalStateException("interrupted", interruption),
                        failure -> new IllegalStateException("failed", failure));
            } catch (Throwable failure) {
                outcome.set(failure);
            } finally {
                callerExited.countDown();
            }
        });
        try {
            caller.start();
            assertTrue(hookStarted.await(1, TimeUnit.SECONDS));
            caller.interrupt();
            assertTrue(callerExited.await(1, TimeUnit.SECONDS));
            assertTrue(outcome.get() instanceof IllegalStateException);

            releaseHook.countDown();
            assertTrue(reported.await(1, TimeUnit.SECONDS));
            assertTrue(eventuallyTrue(() ->
                    BoundedTaskRunner.WORKER_HOOKS.availablePermits() == BoundedTaskRunner.WORKER_HOOKS.capacity()));
            Thread.sleep(50);
            assertEquals(1, matchingReports.get());
        } finally {
            releaseHook.countDown();
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(1));
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Test
    void boundedTimeoutReturnsZeroAfterDeadline() {
        Duration bounded = WorkerHookSupport.boundedTimeout(Duration.ofSeconds(1), System.nanoTime() - 1);

        assertEquals(Duration.ZERO, bounded);
    }

    @Test
    void boundedTimeoutDoesNotExceedHookTimeoutOrRemainingDeadline() {
        Duration hookTimeout = Duration.ofSeconds(5);
        Duration remaining = Duration.ofMillis(200);

        Duration bounded = WorkerHookSupport.boundedTimeout(hookTimeout, System.nanoTime() + remaining.toNanos());

        assertTrue(bounded.compareTo(Duration.ZERO) > 0);
        assertTrue(bounded.compareTo(hookTimeout) <= 0);
        assertTrue(bounded.compareTo(remaining) <= 0);
    }

    private static boolean eventuallyTrue(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (System.nanoTime() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
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
