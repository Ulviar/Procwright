/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class BoundedDestroyDispatcherTest extends ProcessLifecycleSharedSupport {

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

    @Test
    void blockingDestroyFallbackHasGlobalBoundedCapacity() throws Exception {
        assertTrue(
                eventually(() -> BoundedDestroyDispatcher.availablePermits() == BoundedDestroyDispatcher.capacity()));
        int baselineCapacity = BoundedDestroyDispatcher.availablePermits();
        assertEquals(BoundedDestroyDispatcher.capacity(), baselineCapacity);
        List<BlockingDestroyProcess> processes = new ArrayList<>();
        try {
            for (int index = 0; index < baselineCapacity; index++) {
                BlockingDestroyProcess process = new BlockingDestroyProcess();
                processes.add(process);
                assertThrows(
                        RuntimeException.class,
                        () -> ProcessLifecycle.forceStop(process, KnownDescendants.empty(), Duration.ZERO));
            }

            assertEquals(0, BoundedDestroyDispatcher.availablePermits());
            BlockingDestroyProcess rejected = new BlockingDestroyProcess();
            processes.add(rejected);
            RuntimeException failure = assertThrows(
                    RuntimeException.class,
                    () -> ProcessLifecycle.forceStop(rejected, KnownDescendants.empty(), Duration.ZERO));

            failureSourceContaining(failure, "bounded destroy capacity is exhausted");
            assertEquals(0, rejected.startedCalls(), "capacity rejection must not start another fallback thread");
        } finally {
            processes.forEach(BlockingDestroyProcess::release);
        }
        assertTrue(eventually(() -> BoundedDestroyDispatcher.availablePermits() == baselineCapacity));
        assertTrue(processes.stream().allMatch(process -> process.finishedCalls() == process.startedCalls()));
    }

    private static final class BlockingDestroyProcess extends Process {

        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger started = new AtomicInteger();
        private final AtomicInteger finished = new AtomicInteger();

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() throws InterruptedException {
            release.await();
            return 143;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive.get();
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 143;
        }

        @Override
        public void destroy() {
            blockDestroy();
        }

        @Override
        public Process destroyForcibly() {
            blockDestroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public ProcessHandle toHandle() {
            throw new UnsupportedOperationException("no process handle");
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        private void blockDestroy() {
            started.incrementAndGet();
            boolean interrupted = false;
            try {
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
                alive.set(false);
            } finally {
                finished.incrementAndGet();
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }

        private void release() {
            release.countDown();
        }

        private int startedCalls() {
            return started.get();
        }

        private int finishedCalls() {
            return finished.get();
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
