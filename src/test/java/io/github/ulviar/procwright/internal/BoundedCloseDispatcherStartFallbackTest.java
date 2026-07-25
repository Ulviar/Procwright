/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static io.github.ulviar.procwright.internal.BoundedCloseDispatcherTestAccess.dispatch;
import static io.github.ulviar.procwright.internal.BoundedCloseDispatcherTestAccess.dispatchPair;
import static io.github.ulviar.procwright.internal.BoundedCloseDispatcherTestAccess.request;
import static io.github.ulviar.procwright.internal.BoundedCloseDispatcherTestSupport.awaitUninterruptibly;
import static io.github.ulviar.procwright.internal.BoundedCloseDispatcherTestSupport.throwCloseFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

final class BoundedCloseDispatcherStartFallbackTest {

    @Test
    void threadStartFailureClosesPhysicallyAndBlockingCallbackCannotStrandQueuedFifoWork() throws Exception {
        IllegalStateException startFailure = new IllegalStateException("thread start failed");
        AtomicInteger startAttempts = new AtomicInteger();
        CountDownLatch firstStartEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstStart = new CountDownLatch(1);
        CountDownLatch failedCloseCompleted = new CountDownLatch(1);
        CountDownLatch failureCallbackEntered = new CountDownLatch(1);
        CountDownLatch releaseFailureCallback = new CountDownLatch(1);
        CountDownLatch secondClosed = new CountDownLatch(1);
        CountDownLatch thirdClosed = new CountDownLatch(1);
        CountDownLatch thirdSettled = new CountDownLatch(1);
        List<Integer> closeOrder = Collections.synchronizedList(new ArrayList<>());
        BoundedFailureReporter reporter = new BoundedFailureReporter(2, 6);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(
                1,
                2,
                (name, task) -> {
                    if (startAttempts.getAndIncrement() == 0) {
                        firstStartEntered.countDown();
                        awaitUninterruptibly(releaseFirstStart);
                        throw startFailure;
                    }
                    Thread thread = new Thread(task, name + startAttempts.get());
                    thread.setDaemon(true);
                    thread.start();
                },
                reporter);
        AtomicInteger failedCloseCalls = new AtomicInteger();
        AtomicInteger failureReports = new AtomicInteger();
        AtomicReference<Throwable> reported = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> failedDispatch = executor.submit(() -> dispatch(
                dispatcher.reserve(1),
                () -> {
                    failedCloseCalls.incrementAndGet();
                    closeOrder.add(0);
                    failedCloseCompleted.countDown();
                },
                "procwright-start-failure-",
                failure -> {
                    failureReports.incrementAndGet();
                    reported.set(failure);
                    failureCallbackEntered.countDown();
                    awaitUninterruptibly(releaseFailureCallback);
                }));
        try {
            assertTrue(firstStartEntered.await(1, TimeUnit.SECONDS));
            dispatch(
                    dispatcher.reserve(1),
                    () -> {
                        closeOrder.add(1);
                        secondClosed.countDown();
                    },
                    "procwright-after-start-failure-",
                    failure -> {});
            dispatch(
                    dispatcher.reserve(1),
                    () -> {
                        closeOrder.add(2);
                        thirdClosed.countDown();
                    },
                    "procwright-third-close-",
                    failure -> {},
                    thirdSettled::countDown);
            assertEquals(2, dispatcher.pendingCount(), "accepted work must wait behind the FIFO head");

            releaseFirstStart.countDown();

            assertTrue(failedCloseCompleted.await(1, TimeUnit.SECONDS));
            assertTrue(failureCallbackEntered.await(1, TimeUnit.SECONDS));
            assertTrue(secondClosed.await(1, TimeUnit.SECONDS));
            assertTrue(thirdClosed.await(1, TimeUnit.SECONDS));
            assertTrue(thirdSettled.await(1, TimeUnit.SECONDS));
            assertSame(startFailure, reported.get());
            assertEquals(1, failureReports.get());
            assertEquals(1, failedCloseCalls.get());
            assertEquals(List.of(0, 1, 2), closeOrder);
            assertEquals(0, dispatcher.outstandingCount());
        } finally {
            releaseFirstStart.countDown();
            releaseFailureCallback.countDown();
            ExecutionException dispatchFailure =
                    assertThrows(ExecutionException.class, () -> failedDispatch.get(1, TimeUnit.SECONDS));
            assertSame(startFailure, dispatchFailure.getCause());
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void threadStartFailureNeverRunsBlockingPhysicalCloseOnDispatchCaller() throws Exception {
        IllegalStateException startFailure = new IllegalStateException("close worker start failed");
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        CountDownLatch failureReported = new CountDownLatch(1);
        AtomicInteger closeCalls = new AtomicInteger();
        AtomicReference<Throwable> reported = new AtomicReference<>();
        AtomicReference<Thread> closeThread = new AtomicReference<>();
        AtomicReference<Thread> dispatchThread = new AtomicReference<>();
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 1, (name, task) -> {
            throw startFailure;
        });
        ExecutorService caller = Executors.newSingleThreadExecutor();
        Future<?> dispatch = caller.submit(() -> {
            dispatchThread.set(Thread.currentThread());
            dispatch(
                    dispatcher.reserve(1),
                    () -> {
                        closeCalls.incrementAndGet();
                        closeThread.set(Thread.currentThread());
                        closeEntered.countDown();
                        awaitUninterruptibly(releaseClose);
                    },
                    "procwright-rejected-blocking-close-",
                    failure -> {
                        reported.set(failure);
                        failureReported.countDown();
                    });
        });
        try {
            ExecutionException dispatchFailure =
                    assertThrows(ExecutionException.class, () -> dispatch.get(1, TimeUnit.SECONDS));

            assertSame(startFailure, dispatchFailure.getCause());
            assertTrue(closeEntered.await(1, TimeUnit.SECONDS));
            assertFalse(closeThread.get() == dispatchThread.get());
            assertEquals(1, dispatcher.activeCount());
            assertEquals(1, dispatcher.outstandingCount());
        } finally {
            releaseClose.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(1, TimeUnit.SECONDS));
        }

        assertTrue(failureReported.await(1, TimeUnit.SECONDS));
        assertSame(startFailure, reported.get());
        assertEquals(1, closeCalls.get());
        assertEquals(0, dispatcher.activeCount());
        assertEquals(0, dispatcher.outstandingCount());
    }

    @TestFactory
    Stream<DynamicTest> blockingStarterReentryAndSaturationCannotStrandTheDrainer() {
        return Stream.of(1, 2).flatMap(activeCapacity -> Stream.of(0, 1).flatMap(blockingOrdinal -> Stream.of(
                        CloseFailureKind.values())
                .map(failureKind -> DynamicTest.dynamicTest(
                        "active=" + activeCapacity + ", blocking=" + blockingOrdinal + ", failure=" + failureKind,
                        () -> runBlockingStarterCase(activeCapacity, blockingOrdinal, failureKind)))));
    }

    private static void runBlockingStarterCase(int activeCapacity, int blockingOrdinal, CloseFailureKind failureKind)
            throws Exception {
        int pendingCapacity = 3;
        int totalCapacity = activeCapacity + pendingCapacity;
        AtomicInteger starts = new AtomicInteger();
        AtomicReference<Throwable> unexpectedUncaught = new AtomicReference<>();
        CountDownLatch starterBlocked = new CountDownLatch(1);
        CountDownLatch releaseStarter = new CountDownLatch(1);
        List<Thread> closeThreads = new CopyOnWriteArrayList<>();
        BoundedCloseDispatcher dispatcher =
                new BoundedCloseDispatcher(activeCapacity, pendingCapacity, (name, task) -> {
                    int ordinal = starts.getAndIncrement();
                    Thread thread = new Thread(task, name + ordinal);
                    thread.setDaemon(true);
                    thread.setUncaughtExceptionHandler(
                            (ignored, failure) -> unexpectedUncaught.compareAndSet(null, failure));
                    closeThreads.add(thread);
                    thread.start();
                    if (ordinal == blockingOrdinal) {
                        starterBlocked.countDown();
                        awaitWithin(releaseStarter, "close worker did not publish while its starter was blocked");
                    }
                });

        Throwable firstFailure = failureKind.create("first close failed");
        Throwable secondFailure = failureKind.create("second close failed");
        Throwable blockedFailure = blockingOrdinal == 0 ? firstFailure : secondFailure;
        AtomicInteger firstCloses = new AtomicInteger();
        AtomicInteger secondCloses = new AtomicInteger();
        AtomicInteger reentrantCloses = new AtomicInteger();
        List<Throwable> observedFailures = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> callbackFailure = new AtomicReference<>();
        AtomicBoolean reentered = new AtomicBoolean();
        CountDownLatch callbacksCompleted = new CountDownLatch(2);
        CountDownLatch reentrantCloseCompleted = new CountDownLatch(1);
        BoundedCloseDispatcher.Reservation pair = dispatcher.reserve(2);
        BoundedCloseDispatcher.Permit firstPermit = pair.takePermit();
        BoundedCloseDispatcher.Permit secondPermit = pair.takePermit();
        BoundedCloseDispatcher.Reservation saturation = dispatcher.reserve(totalCapacity - 2);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> dispatcher.reserve(1));

        Consumer<Throwable> failureHandler = failure -> {
            try {
                observedFailures.add(failure);
                if (reentered.compareAndSet(false, true)) {
                    dispatch(
                            dispatcher.reserve(1),
                            () -> {
                                reentrantCloses.incrementAndGet();
                                reentrantCloseCompleted.countDown();
                            },
                            "procwright-reentrant-saturated-close-",
                            ignored -> {});
                }
            } catch (Throwable failureDuringCallback) {
                callbackFailure.compareAndSet(null, failureDuringCallback);
            } finally {
                callbacksCompleted.countDown();
            }
        };

        Future<?> dispatch = executor.submit(() -> dispatchPair(
                firstPermit,
                request(
                        () -> {
                            firstCloses.incrementAndGet();
                            throwCloseFailure(firstFailure);
                        },
                        "procwright-blocking-first-close-",
                        failureHandler),
                secondPermit,
                request(
                        () -> {
                            secondCloses.incrementAndGet();
                            throwCloseFailure(secondFailure);
                        },
                        "procwright-blocking-second-close-",
                        failureHandler)));
        try {
            assertTrue(starterBlocked.await(1, TimeUnit.SECONDS));
            releaseStarter.countDown();
            assertTrue(callbacksCompleted.await(1, TimeUnit.SECONDS));
            assertTrue(reentrantCloseCompleted.await(1, TimeUnit.SECONDS));
            dispatch.get(1, TimeUnit.SECONDS);
        } finally {
            releaseStarter.countDown();
            saturation.release();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            for (Thread closeThread : closeThreads) {
                closeThread.join(TimeUnit.SECONDS.toMillis(1));
                assertFalse(closeThread.isAlive());
            }
        }

        assertNull(callbackFailure.get());
        assertNull(unexpectedUncaught.get());
        assertEquals(
                1,
                observedFailures.stream()
                        .filter(failure -> failure == firstFailure)
                        .count());
        assertEquals(
                1,
                observedFailures.stream()
                        .filter(failure -> failure == secondFailure)
                        .count());
        assertEquals(1, firstCloses.get());
        assertEquals(1, secondCloses.get());
        assertEquals(1, reentrantCloses.get());
        assertEquals(3, starts.get());
        assertEquals(0, dispatcher.activeCount());
        assertEquals(0, dispatcher.pendingCount());
        assertEquals(0, dispatcher.outstandingCount());
    }

    private static void awaitWithin(CountDownLatch latch, String failureMessage) {
        boolean interrupted = false;
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
            while (latch.getCount() != 0) {
                long remaining = deadline - System.nanoTime();
                if (remaining <= 0) {
                    throw new AssertionError(failureMessage);
                }
                try {
                    if (!latch.await(remaining, TimeUnit.NANOSECONDS)) {
                        throw new AssertionError(failureMessage);
                    }
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private enum CloseFailureKind {
        IO {
            @Override
            Throwable create(String message) {
                return new IOException(message);
            }
        },
        RUNTIME {
            @Override
            Throwable create(String message) {
                return new IllegalStateException(message);
            }
        },
        ERROR {
            @Override
            Throwable create(String message) {
                return new AssertionError(message);
            }
        };

        abstract Throwable create(String message);
    }
}
