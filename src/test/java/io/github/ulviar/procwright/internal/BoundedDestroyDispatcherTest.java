/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class BoundedDestroyDispatcherTest {

    @Test
    void immediateErrorPreservesIdentityWithoutSchedulerTiming() {
        BoundedDestroyDispatcher.Limiter limiter = new BoundedDestroyDispatcher.Limiter(1);
        int baselineCapacity = limiter.capacity();
        assertEquals(baselineCapacity, limiter.availablePermits());
        AssertionError failure = new AssertionError("destroy failed");

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> BoundedDestroyDispatcher.dispatch(
                        "procwright-immediate-destroy-test-",
                        () -> {
                            throw failure;
                        },
                        limiter,
                        completion -> completion.get()));

        assertSame(failure, thrown);
        assertEquals(baselineCapacity, limiter.availablePermits());
    }

    @Test
    void observationInterruptionIsTypedAndLeavesStatusClearForTheLifecycleOwner() {
        BoundedDestroyDispatcher.Limiter limiter = new BoundedDestroyDispatcher.Limiter(1);
        int baselineCapacity = limiter.capacity();
        assertEquals(baselineCapacity, limiter.availablePermits());
        InterruptedException interruption = new InterruptedException("controlled observation interruption");
        try {
            CommandExecutionException thrown = assertThrows(
                    CommandExecutionException.class,
                    () -> BoundedDestroyDispatcher.dispatch(
                            "procwright-interrupted-destroy-test-", () -> {}, limiter, completion -> {
                                completion.get();
                                Thread.currentThread().interrupt();
                                throw interruption;
                            }));

            assertEquals(CommandExecutionException.Reason.RUNTIME_FAILURE, thrown.reason());
            assertSame(interruption, thrown.getCause());
            assertFalse(Thread.currentThread().isInterrupted());
            assertEquals(baselineCapacity, limiter.availablePermits());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void abandonedLateErrorIsReportedOnceAndRetainsPermitUntilCompletion() throws Exception {
        BoundedDestroyDispatcher.Limiter limiter = new BoundedDestroyDispatcher.Limiter(1);
        int baselineCapacity = limiter.capacity();
        assertEquals(baselineCapacity, limiter.availablePermits());
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch reported = new CountDownLatch(1);
        AssertionError failure = new AssertionError("late destroy failed");
        AtomicInteger reportCount = new AtomicInteger();
        AtomicReference<Throwable> reportedFailure = new AtomicReference<>();
        AtomicReference<CompletableFuture<Void>> terminal = new AtomicReference<>();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, uncaught) -> {
            reportCount.incrementAndGet();
            reportedFailure.compareAndSet(null, uncaught);
            reported.countDown();
        });
        try {
            BoundedDestroyDispatcher.dispatch(
                    "procwright-late-destroy-test-",
                    () -> {
                        started.countDown();
                        awaitIgnoringInterrupts(release);
                        throw failure;
                    },
                    limiter,
                    completion -> {
                        terminal.set(completion);
                        assertTrue(started.await(1, TimeUnit.SECONDS));
                        throw new TimeoutException("controlled abandonment");
                    });

            assertEquals(0, limiter.availablePermits());
            release.countDown();
            ExecutionException terminalFailure =
                    assertThrows(ExecutionException.class, () -> terminal.get().get(1, TimeUnit.SECONDS));
            assertSame(failure, terminalFailure.getCause());
            assertEquals(baselineCapacity, limiter.availablePermits());
            assertTrue(reported.await(1, TimeUnit.SECONDS));
            assertSame(failure, reportedFailure.get());
            assertEquals(1, reportCount.get());
        } finally {
            release.countDown();
            CompletableFuture<Void> completion = terminal.get();
            if (completion != null) {
                completion.handle((ignored, failureIgnored) -> null).get(1, TimeUnit.SECONDS);
            }
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Test
    void blockedLateFailureHandlerCannotRetainCompletionOrDestroyCapacity() throws Exception {
        BoundedDestroyDispatcher.Limiter limiter = new BoundedDestroyDispatcher.Limiter(1);
        CountDownLatch actionStarted = new CountDownLatch(1);
        CountDownLatch releaseAction = new CountDownLatch(1);
        CountDownLatch handlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandler = new CountDownLatch(1);
        AtomicReference<CompletableFuture<Void>> terminal = new AtomicReference<>();
        AssertionError failure = new AssertionError("late destroy failed");
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, uncaught) -> {
            assertSame(failure, uncaught);
            handlerEntered.countDown();
            awaitIgnoringInterrupts(releaseHandler);
        });
        try {
            BoundedDestroyDispatcher.dispatch(
                    "procwright-blocked-destroy-report-",
                    () -> {
                        actionStarted.countDown();
                        awaitIgnoringInterrupts(releaseAction);
                        throw failure;
                    },
                    limiter,
                    completion -> {
                        terminal.set(completion);
                        assertTrue(actionStarted.await(1, TimeUnit.SECONDS));
                        throw new TimeoutException("controlled abandonment");
                    });

            releaseAction.countDown();
            assertTrue(handlerEntered.await(1, TimeUnit.SECONDS));
            ExecutionException terminalFailure =
                    assertThrows(ExecutionException.class, () -> terminal.get().get(1, TimeUnit.SECONDS));
            assertSame(failure, terminalFailure.getCause());
            assertEquals(1, limiter.availablePermits(), "internal capacity must precede external reporting");
        } finally {
            releaseAction.countDown();
            releaseHandler.countDown();
            Thread.setDefaultUncaughtExceptionHandler(previous);
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
