/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import io.github.ulviar.procwright.internal.Threading;
import java.io.IOException;
import java.io.InputStream;
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

final class BoundedCloseDispatcherTest {

    @Test
    void blockedFailureHandlersCannotLeakCloseCapacityOrCreateUnboundedReportWork() throws Exception {
        int closeCount = 12;
        BoundedFailureReporter reporter = new BoundedFailureReporter(1, 2);
        CountDownLatch firstHandlerEntered = new CountDownLatch(1);
        CountDownLatch releaseHandlers = new CountDownLatch(1);
        CountDownLatch allClosed = new CountDownLatch(closeCount);
        List<AssertionError> failures = new ArrayList<>();
        List<Thread> closeOwners = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> firstObserved = new AtomicReference<>();
        BoundedCloseDispatcher.ThreadStarter starter = (name, task) -> {
            Thread owner = Threading.unstarted(name, task);
            closeOwners.add(owner);
            owner.start();
            return owner;
        };
        BoundedCloseDispatcher dispatcher =
                new BoundedCloseDispatcher(2, closeCount - 2, closeCount, starter, reporter);
        try {
            for (int ordinal = 0; ordinal < closeCount; ordinal++) {
                AssertionError failure = new AssertionError("close " + ordinal);
                failures.add(failure);
                dispatcher
                        .reserve(1)
                        .dispatch(
                                () -> {
                                    allClosed.countDown();
                                    throw failure;
                                },
                                "procwright-bounded-report-close-",
                                observed -> {
                                    firstObserved.compareAndSet(null, observed);
                                    firstHandlerEntered.countDown();
                                    awaitUninterruptibly(releaseHandlers);
                                });
            }

            assertTrue(firstHandlerEntered.await(1, TimeUnit.SECONDS));
            assertTrue(allClosed.await(1, TimeUnit.SECONDS));
            assertEquals(closeCount, closeOwners.size());
            for (Thread closeOwner : closeOwners) {
                closeOwner.join(TimeUnit.SECONDS.toMillis(1));
                assertFalse(closeOwner.isAlive());
            }
            assertEquals(0, dispatcher.outstandingCount());
            assertTrue(failures.stream().anyMatch(failure -> failure == firstObserved.get()));
            assertTrue(reporter.activeCount() <= reporter.workerCapacity());
            assertTrue(reporter.queuedCount() <= reporter.queueCapacity());
        } finally {
            releaseHandlers.countDown();
        }
    }

    @Test
    void capacityOneQueuesReservedClosesInFifoOrderAndAdvancesBeforeFailureCallbackReturns() throws Exception {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2, 3);
        IOException stdoutFailure = new IOException("stdout close failed");
        CountDownLatch stdoutCloseStarted = new CountDownLatch(1);
        CountDownLatch releaseStdoutClose = new CountDownLatch(1);
        CountDownLatch stdoutCallbackEntered = new CountDownLatch(1);
        CountDownLatch releaseStdoutCallback = new CountDownLatch(1);
        CountDownLatch stdoutCallbackFinished = new CountDownLatch(1);
        CountDownLatch stderrClosed = new CountDownLatch(1);
        AtomicInteger stdoutCloseCalls = new AtomicInteger();
        AtomicInteger stderrCloseCalls = new AtomicInteger();
        AtomicReference<Throwable> observedStdoutFailure = new AtomicReference<>();
        List<String> closeOrder = Collections.synchronizedList(new ArrayList<>());

        try {
            dispatcher
                    .reserve(1)
                    .dispatch(
                            new BlockingFailingCloseInputStream(
                                    "stdout",
                                    stdoutFailure,
                                    stdoutCloseCalls,
                                    closeOrder,
                                    stdoutCloseStarted,
                                    releaseStdoutClose),
                            "procwright-queued-stdout-close-",
                            failure -> {
                                observedStdoutFailure.set(failure);
                                stdoutCallbackEntered.countDown();
                                try {
                                    awaitUninterruptibly(releaseStdoutCallback);
                                } finally {
                                    stdoutCallbackFinished.countDown();
                                }
                            });
            assertTrue(stdoutCloseStarted.await(1, TimeUnit.SECONDS));

            dispatcher
                    .reserve(1)
                    .dispatch(
                            new RecordingCloseInputStream("stderr", stderrCloseCalls, closeOrder, stderrClosed),
                            "procwright-queued-stderr-close-",
                            failure -> {});
            assertEquals(0, stderrCloseCalls.get(), "stderr must remain pending while stdout owns the only permit");

            releaseStdoutClose.countDown();

            assertTrue(stdoutCallbackEntered.await(1, TimeUnit.SECONDS));
            assertTrue(
                    stderrClosed.await(1, TimeUnit.SECONDS),
                    "a blocking failure callback must not strand the next physical close");
            assertSame(stdoutFailure, observedStdoutFailure.get());
            assertEquals(List.of("stdout", "stderr"), closeOrder);
            assertEquals(1, stdoutCloseCalls.get());
            assertEquals(1, stderrCloseCalls.get());
        } finally {
            releaseStdoutClose.countDown();
            releaseStdoutCallback.countDown();
        }
        assertTrue(stdoutCallbackFinished.await(1, TimeUnit.SECONDS), "failure callback thread must terminate");
    }

    @Test
    void physicalCloseReleasesCapacityBeforeCallingTheFailureHandler() throws Exception {
        IOException closeFailure = new IOException("first close failed");
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CountDownLatch secondClose = new CountDownLatch(1);
        AtomicInteger activeObservedByCallback = new AtomicInteger(-1);
        AtomicReference<Throwable> observedFailure = new AtomicReference<>();
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 1, 2);

        try {
            dispatcher
                    .reserve(1)
                    .dispatch(new FailingCloseInputStream(closeFailure), "procwright-first-output-close-", failure -> {
                        observedFailure.set(failure);
                        activeObservedByCallback.set(dispatcher.activeCount());
                        callbackEntered.countDown();
                        awaitUninterruptibly(releaseCallback);
                    });

            assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));
            assertSame(closeFailure, observedFailure.get());
            assertEquals(0, activeObservedByCallback.get(), "failure reporting must not retain close capacity");

            dispatcher
                    .reserve(1)
                    .dispatch(
                            new CloseSignalInputStream(secondClose), "procwright-second-output-close-", failure -> {});
            assertTrue(
                    secondClose.await(1, TimeUnit.SECONDS), "the next physical close must not wait for the callback");
        } finally {
            releaseCallback.countDown();
        }
    }

    @Test
    void validatesCapacityConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedCloseDispatcher(0, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BoundedCloseDispatcher(1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new BoundedCloseDispatcher(1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new BoundedCloseDispatcher(1, 1, 3));
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 1, 2);
        assertThrows(IllegalArgumentException.class, () -> dispatcher.reserve(0));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.reserve(3));
    }

    @Test
    void admissionIsAtomicBoundedAndRejectionLeavesCleanupWithTheCaller() throws Exception {
        BoundedFailureReporter reporter = new BoundedFailureReporter(1, 3);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2, 3, Threading::start, reporter);
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch acceptedClosed = new CountDownLatch(3);
        CountDownLatch acceptedSettled = new CountDownLatch(3);
        CountDownLatch callerClosedRejectedResource = new CountDownLatch(1);
        List<Integer> closeOrder = Collections.synchronizedList(new ArrayList<>());
        for (int ordinal = 0; ordinal < 3; ordinal++) {
            TrackingCloseable request = new TrackingCloseable(
                    ordinal,
                    closeOrder,
                    acceptedClosed,
                    ordinal == 0 ? firstStarted : null,
                    ordinal == 0 ? releaseFirst : null);
            dispatcher
                    .reserve(1)
                    .dispatch(request, "procwright-bounded-close-", failure -> {}, acceptedSettled::countDown);
            if (ordinal == 0) {
                assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            }
        }

        assertEquals(1, dispatcher.activeCount());
        assertEquals(2, dispatcher.pendingCount());
        assertEquals(3, dispatcher.outstandingCount());
        assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> dispatcher.reserve(1));
        new CloseSignalInputStream(callerClosedRejectedResource).close();
        assertTrue(callerClosedRejectedResource.await(1, TimeUnit.SECONDS));

        releaseFirst.countDown();
        assertTrue(acceptedClosed.await(1, TimeUnit.SECONDS));
        assertTrue(acceptedSettled.await(1, TimeUnit.SECONDS));
        assertEquals(List.of(0, 1, 2), closeOrder);
        assertEquals(0, dispatcher.activeCount());
        assertEquals(0, dispatcher.pendingCount());
        assertEquals(0, dispatcher.outstandingCount());
    }

    @Test
    void pendingCapacityRemainsExactWhileAnotherActiveStarterIsBlocked() throws Exception {
        CountDownLatch firstStarterEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstStarter = new CountDownLatch(1);
        CountDownLatch secondCloseEntered = new CountDownLatch(1);
        CountDownLatch releaseSecondClose = new CountDownLatch(1);
        CountDownLatch allClosed = new CountDownLatch(3);
        CountDownLatch allSettled = new CountDownLatch(3);
        AtomicInteger starts = new AtomicInteger();
        BoundedFailureReporter reporter = new BoundedFailureReporter(1, 3);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(
                2,
                1,
                3,
                (name, task) -> {
                    int ordinal = starts.getAndIncrement();
                    if (ordinal == 0) {
                        firstStarterEntered.countDown();
                        awaitUninterruptibly(releaseFirstStarter);
                    }
                    Thread worker = new Thread(task, name + ordinal);
                    worker.setDaemon(true);
                    worker.start();
                    return worker;
                },
                reporter);
        ExecutorService firstCaller = Executors.newSingleThreadExecutor();
        Future<?> firstDispatch = firstCaller.submit(() -> dispatcher
                .reserve(1)
                .dispatch(
                        allClosed::countDown,
                        "procwright-blocked-first-starter-",
                        ignored -> {},
                        allSettled::countDown));
        try {
            assertTrue(firstStarterEntered.await(1, TimeUnit.SECONDS));

            dispatcher
                    .reserve(1)
                    .dispatch(
                            () -> {
                                secondCloseEntered.countDown();
                                awaitUninterruptibly(releaseSecondClose);
                                allClosed.countDown();
                            },
                            "procwright-second-active-close-",
                            ignored -> {},
                            allSettled::countDown);
            assertTrue(
                    secondCloseEntered.await(1, TimeUnit.SECONDS),
                    "the second active slot must remain usable while the first starter blocks");

            dispatcher
                    .reserve(1)
                    .dispatch(
                            allClosed::countDown,
                            "procwright-only-pending-close-",
                            ignored -> {},
                            allSettled::countDown);

            assertEquals(2, dispatcher.activeCount());
            assertEquals(1, dispatcher.pendingCount());
            assertEquals(3, dispatcher.outstandingCount());
            assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> dispatcher.reserve(1));
        } finally {
            releaseFirstStarter.countDown();
            releaseSecondClose.countDown();
            firstCaller.shutdownNow();
            assertTrue(firstCaller.awaitTermination(1, TimeUnit.SECONDS));
        }

        firstDispatch.get(1, TimeUnit.SECONDS);
        assertTrue(allClosed.await(1, TimeUnit.SECONDS));
        assertTrue(allSettled.await(1, TimeUnit.SECONDS));
        assertEquals(0, dispatcher.activeCount());
        assertEquals(0, dispatcher.pendingCount());
        assertEquals(0, dispatcher.outstandingCount());
    }

    @Test
    void pairAdmissionRejectsAtomicallyAndPartialUseReleasesOnlyTheUnusedPermit() throws Exception {
        AtomicReference<Thread> closeThread = new AtomicReference<>();
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 1, 2, (name, task) -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            closeThread.set(thread);
            thread.start();
            return thread;
        });
        CountDownLatch closed = new CountDownLatch(1);

        BoundedCloseDispatcher.Reservation pair = dispatcher.reserve(2);

        assertEquals(2, dispatcher.outstandingCount());
        assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> dispatcher.reserve(1));
        pair.dispatch(new CloseSignalInputStream(closed), "procwright-partial-reservation-", failure -> {});
        pair.release();
        pair.release();
        assertTrue(closed.await(1, TimeUnit.SECONDS));
        closeThread.get().join(TimeUnit.SECONDS.toMillis(1));
        assertFalse(closeThread.get().isAlive());
        assertThrows(
                IllegalStateException.class,
                () -> pair.dispatch(() -> {}, "procwright-double-dispatch-", failure -> {}));
        assertEquals(0, dispatcher.outstandingCount());
    }

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
                3,
                (name, task) -> {
                    if (startAttempts.getAndIncrement() == 0) {
                        firstStartEntered.countDown();
                        awaitUninterruptibly(releaseFirstStart);
                        throw startFailure;
                    }
                    Thread thread = new Thread(task, name + startAttempts.get());
                    thread.setDaemon(true);
                    thread.start();
                    return thread;
                },
                reporter);
        AtomicInteger failedCloseCalls = new AtomicInteger();
        AtomicInteger failureReports = new AtomicInteger();
        AtomicReference<Throwable> reported = new AtomicReference<>();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> failedDispatch = executor.submit(() -> dispatcher
                .reserve(1)
                .dispatch(
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
            dispatcher
                    .reserve(1)
                    .dispatch(
                            () -> {
                                closeOrder.add(1);
                                secondClosed.countDown();
                            },
                            "procwright-after-start-failure-",
                            failure -> {});
            dispatcher
                    .reserve(1)
                    .dispatch(
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
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 1, 2, (name, task) -> {
            throw startFailure;
        });
        ExecutorService caller = Executors.newSingleThreadExecutor();
        Future<?> dispatch = caller.submit(() -> {
            dispatchThread.set(Thread.currentThread());
            dispatcher
                    .reserve(1)
                    .dispatch(
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

    @Test
    void startThenThrowAndCloseFailureShareOneFallbackFailureGraph() throws Exception {
        IllegalStateException startFailure = new IllegalStateException("starter failed after starting worker");
        IOException closeFailure = new IOException("fallback close failed");
        AtomicInteger physicalCloses = new AtomicInteger();
        AtomicInteger completions = new AtomicInteger();
        AtomicInteger failureReports = new AtomicInteger();
        AtomicReference<Throwable> reported = new AtomicReference<>();
        AtomicReference<Thread> closeThread = new AtomicReference<>();
        AtomicReference<Thread> dispatchThread = new AtomicReference<>();
        CountDownLatch completed = new CountDownLatch(1);
        CountDownLatch failureReported = new CountDownLatch(1);
        CountDownLatch rejectedWorkerStopped = new CountDownLatch(1);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 1, 2, (name, task) -> {
            Thread worker = new Thread(
                    () -> {
                        try {
                            task.run();
                        } finally {
                            rejectedWorkerStopped.countDown();
                        }
                    },
                    name);
            worker.setDaemon(true);
            worker.start();
            throw startFailure;
        });

        dispatchThread.set(Thread.currentThread());
        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> dispatcher
                .reserve(1)
                .dispatch(
                        () -> {
                            physicalCloses.incrementAndGet();
                            closeThread.set(Thread.currentThread());
                            throw closeFailure;
                        },
                        "procwright-partial-start-close-",
                        failure -> {
                            failureReports.incrementAndGet();
                            reported.set(failure);
                            failureReported.countDown();
                        },
                        () -> {
                            completions.incrementAndGet();
                            completed.countDown();
                        }));

        assertSame(startFailure, thrown);
        assertTrue(rejectedWorkerStopped.await(1, TimeUnit.SECONDS));
        assertTrue(completed.await(1, TimeUnit.SECONDS));
        assertTrue(failureReported.await(1, TimeUnit.SECONDS));
        assertSame(startFailure, reported.get());
        assertEquals(List.of(closeFailure), List.of(startFailure.getSuppressed()));
        assertFalse(closeThread.get() == dispatchThread.get());
        assertEquals(1, failureReports.get());
        assertEquals(1, physicalCloses.get());
        assertEquals(1, completions.get());
        assertEquals(0, dispatcher.activeCount());
        assertEquals(0, dispatcher.pendingCount());
        assertEquals(0, dispatcher.outstandingCount());
    }

    @Test
    void inlineStartThenThrowNeverRunsPhysicalCloseOnDispatchCaller() throws Exception {
        IllegalStateException startFailure = new IllegalStateException("inline starter failed after invoking task");
        CountDownLatch closeEntered = new CountDownLatch(1);
        CountDownLatch releaseClose = new CountDownLatch(1);
        CountDownLatch closeSettled = new CountDownLatch(1);
        AtomicInteger physicalCloses = new AtomicInteger();
        AtomicReference<Thread> closeThread = new AtomicReference<>();
        Thread dispatchThread = Thread.currentThread();
        BoundedFailureReporter reporter = new BoundedFailureReporter(1, 2);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(
                1,
                1,
                2,
                (name, task) -> {
                    task.run();
                    throw startFailure;
                },
                reporter);

        IllegalStateException thrown = assertThrows(IllegalStateException.class, () -> dispatcher
                .reserve(1)
                .dispatch(
                        () -> {
                            physicalCloses.incrementAndGet();
                            closeThread.set(Thread.currentThread());
                            closeEntered.countDown();
                            awaitUninterruptibly(releaseClose);
                        },
                        "procwright-inline-start-close-",
                        ignored -> {},
                        closeSettled::countDown));
        try {
            assertSame(startFailure, thrown);
            assertTrue(closeEntered.await(1, TimeUnit.SECONDS));
            assertFalse(closeThread.get() == dispatchThread);
        } finally {
            releaseClose.countDown();
        }

        assertTrue(closeSettled.await(1, TimeUnit.SECONDS));
        assertEquals(0, dispatcher.outstandingCount());
        assertEquals(1, physicalCloses.get());
    }

    @Test
    void everyPhysicalCloseFailureKindReleasesAccountingAndReportsTheOriginalFailureOnce() throws Exception {
        for (Throwable expected : List.of(
                new IOException("io failure"),
                new IllegalStateException("runtime failure"),
                new AssertionError("error failure"))) {
            BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 1, 2);
            AtomicInteger reports = new AtomicInteger();
            AtomicReference<Throwable> observed = new AtomicReference<>();
            CountDownLatch reported = new CountDownLatch(1);

            dispatcher
                    .reserve(1)
                    .dispatch(() -> throwCloseFailure(expected), "procwright-failing-output-close-", failure -> {
                        reports.incrementAndGet();
                        observed.set(failure);
                        reported.countDown();
                    });

            assertTrue(reported.await(1, TimeUnit.SECONDS));
            assertSame(expected, observed.get());
            assertEquals(1, reports.get());
            assertEquals(0, dispatcher.activeCount());
            assertEquals(0, dispatcher.pendingCount());
            assertEquals(0, dispatcher.outstandingCount());
        }
    }

    @Test
    void callbackCanReenterDispatcherAfterCapacityRelease() throws Exception {
        BoundedFailureReporter reporter = new BoundedFailureReporter(1, 4);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 1, 2, Threading::start, reporter);
        IOException closeFailure = new IOException("close failed");
        CountDownLatch reentrantClose = new CountDownLatch(1);
        CountDownLatch reentrantSettled = new CountDownLatch(1);
        CountDownLatch callbackFinished = new CountDownLatch(1);

        dispatcher
                .reserve(1)
                .dispatch(new FailingCloseInputStream(closeFailure), "procwright-reentrant-failure-", failure -> {
                    assertSame(closeFailure, failure);
                    dispatcher
                            .reserve(1)
                            .dispatch(
                                    new CloseSignalInputStream(reentrantClose),
                                    "procwright-reentrant-close-",
                                    ignored -> {},
                                    reentrantSettled::countDown);
                    callbackFinished.countDown();
                });

        assertTrue(callbackFinished.await(1, TimeUnit.SECONDS));
        assertTrue(reentrantClose.await(1, TimeUnit.SECONDS));
        assertTrue(reentrantSettled.await(1, TimeUnit.SECONDS));
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

    @Test
    void synchronousStarterIsRejectedAndAcceptedQueueStillCompletesOnFallbackOwner() throws Exception {
        CountDownLatch secondClosed = new CountDownLatch(1);
        CountDownLatch secondSettled = new CountDownLatch(1);
        BoundedFailureReporter reporter = new BoundedFailureReporter(2, 6);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(
                1,
                1,
                2,
                (name, task) -> {
                    task.run();
                    return Thread.currentThread();
                },
                reporter);
        BoundedCloseDispatcher.Reservation pair = dispatcher.reserve(2);
        java.util.concurrent.RejectedExecutionException startFailure = assertThrows(
                java.util.concurrent.RejectedExecutionException.class,
                () -> pair.dispatch(
                        BoundedCloseDispatcher.closeRequest(
                                () -> {}, "procwright-synchronous-first-close-", failure -> {}),
                        BoundedCloseDispatcher.closeRequest(
                                new CloseSignalInputStream(secondClosed),
                                "procwright-synchronous-second-close-",
                                failure -> {},
                                secondSettled::countDown)));

        assertTrue(startFailure.getMessage().contains("asynchronously"));
        assertTrue(secondClosed.await(1, TimeUnit.SECONDS));
        assertTrue(secondSettled.await(1, TimeUnit.SECONDS));
        assertEquals(0, dispatcher.outstandingCount());
    }

    @Test
    void liveReturnedOwnerMismatchFallsBackExactlyOnceAndReportsOneOwnershipFailure() throws Exception {
        CountDownLatch releaseReturnedOwner = new CountDownLatch(1);
        CountDownLatch closed = new CountDownLatch(1);
        CountDownLatch settled = new CountDownLatch(1);
        CountDownLatch failureReported = new CountDownLatch(1);
        AtomicInteger physicalCloses = new AtomicInteger();
        AtomicInteger failureReports = new AtomicInteger();
        AtomicReference<Throwable> reported = new AtomicReference<>();
        AtomicReference<Thread> closeThread = new AtomicReference<>();
        Thread dispatchThread = Thread.currentThread();
        BoundedFailureReporter reporter = new BoundedFailureReporter(1, 3);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(
                1,
                1,
                2,
                (name, task) -> {
                    Thread actualOwner = new Thread(task, name + "actual");
                    Thread returnedOwner =
                            new Thread(() -> awaitUninterruptibly(releaseReturnedOwner), name + "returned");
                    actualOwner.setDaemon(true);
                    returnedOwner.setDaemon(true);
                    actualOwner.start();
                    returnedOwner.start();
                    return returnedOwner;
                },
                reporter);
        try {
            dispatcher
                    .reserve(1)
                    .dispatch(
                            () -> {
                                physicalCloses.incrementAndGet();
                                closeThread.set(Thread.currentThread());
                                closed.countDown();
                            },
                            "procwright-live-owner-mismatch-",
                            failure -> {
                                failureReports.incrementAndGet();
                                reported.set(failure);
                                failureReported.countDown();
                            },
                            settled::countDown);

            assertTrue(closed.await(1, TimeUnit.SECONDS));
            assertTrue(failureReported.await(1, TimeUnit.SECONDS));
            assertTrue(settled.await(1, TimeUnit.SECONDS));
        } finally {
            releaseReturnedOwner.countDown();
        }

        assertTrue(reported.get() instanceof java.util.concurrent.RejectedExecutionException);
        assertTrue(reported.get().getMessage().contains("instead of the thread returned"));
        assertEquals(1, failureReports.get());
        assertEquals(1, physicalCloses.get());
        assertFalse(closeThread.get() == dispatchThread);
        assertEquals(0, dispatcher.activeCount());
        assertEquals(0, dispatcher.pendingCount());
        assertEquals(0, dispatcher.outstandingCount());
    }

    @Test
    void deadReturnedOwnerFallsBackExactlyOnceAndReportsOneOwnershipFailure() throws Exception {
        CountDownLatch closed = new CountDownLatch(1);
        CountDownLatch settled = new CountDownLatch(1);
        CountDownLatch failureReported = new CountDownLatch(1);
        AtomicInteger physicalCloses = new AtomicInteger();
        AtomicInteger failureReports = new AtomicInteger();
        AtomicReference<Throwable> reported = new AtomicReference<>();
        AtomicReference<Thread> closeThread = new AtomicReference<>();
        Thread dispatchThread = Thread.currentThread();
        BoundedFailureReporter reporter = new BoundedFailureReporter(1, 3);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(
                1,
                1,
                2,
                (name, task) -> {
                    Thread actualOwner = new Thread(task, name + "actual");
                    actualOwner.setDaemon(true);
                    actualOwner.start();
                    return new Thread(() -> {}, name + "dead-returned");
                },
                reporter);

        java.util.concurrent.RejectedExecutionException dispatchFailure =
                assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> dispatcher
                        .reserve(1)
                        .dispatch(
                                () -> {
                                    physicalCloses.incrementAndGet();
                                    closeThread.set(Thread.currentThread());
                                    closed.countDown();
                                },
                                "procwright-dead-owner-mismatch-",
                                failure -> {
                                    failureReports.incrementAndGet();
                                    reported.set(failure);
                                    failureReported.countDown();
                                },
                                settled::countDown));

        assertTrue(dispatchFailure.getMessage().contains("returned live thread"));
        assertTrue(closed.await(1, TimeUnit.SECONDS));
        assertTrue(failureReported.await(1, TimeUnit.SECONDS));
        assertTrue(settled.await(1, TimeUnit.SECONDS));
        assertSame(dispatchFailure, reported.get());
        assertEquals(1, failureReports.get());
        assertEquals(1, physicalCloses.get());
        assertFalse(closeThread.get() == dispatchThread);
        assertEquals(0, dispatcher.activeCount());
        assertEquals(0, dispatcher.pendingCount());
        assertEquals(0, dispatcher.outstandingCount());
    }

    @Test
    void callbackFailureIsSuppressedOnTheOriginalCloseFailureAndReportedOnce() throws Exception {
        IOException closeFailure = new IOException("close failed");
        IllegalStateException callbackFailure = new IllegalStateException("callback failed");
        AtomicInteger callbackCalls = new AtomicInteger();
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        CountDownLatch callbackReturned = new CountDownLatch(1);
        CountDownLatch uncaughtReported = new CountDownLatch(1);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 1, 2, (name, task) -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, failure) -> {
                uncaught.set(failure);
                uncaughtReported.countDown();
            });
            thread.start();
            return thread;
        });
        dispatcher
                .reserve(1)
                .dispatch(new FailingCloseInputStream(closeFailure), "procwright-failed-close-", failure -> {
                    callbackCalls.incrementAndGet();
                    callbackReturned.countDown();
                    throw callbackFailure;
                });
        assertTrue(callbackReturned.await(1, TimeUnit.SECONDS));
        assertTrue(uncaughtReported.await(1, TimeUnit.SECONDS));

        assertEquals(1, callbackCalls.get());
        assertSame(closeFailure, uncaught.get());
        assertEquals(List.of(callbackFailure), List.of(uncaught.get().getSuppressed()));
    }

    private static void runBlockingStarterCase(int activeCapacity, int blockingOrdinal, CloseFailureKind failureKind)
            throws Exception {
        int pendingCapacity = 3;
        int maxOutstandingCapacity = activeCapacity + pendingCapacity;
        AtomicInteger starts = new AtomicInteger();
        AtomicReference<Throwable> unexpectedUncaught = new AtomicReference<>();
        CountDownLatch starterBlocked = new CountDownLatch(1);
        CountDownLatch releaseStarter = new CountDownLatch(1);
        List<Thread> closeThreads = new CopyOnWriteArrayList<>();
        BoundedCloseDispatcher dispatcher =
                new BoundedCloseDispatcher(activeCapacity, pendingCapacity, maxOutstandingCapacity, (name, task) -> {
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
                    return thread;
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
        BoundedCloseDispatcher.Reservation saturation = dispatcher.reserve(maxOutstandingCapacity - 2);
        ExecutorService executor = Executors.newSingleThreadExecutor();

        assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> dispatcher.reserve(1));

        Consumer<Throwable> failureHandler = failure -> {
            try {
                observedFailures.add(failure);
                if (reentered.compareAndSet(false, true)) {
                    dispatcher
                            .reserve(1)
                            .dispatch(
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

        Future<?> dispatch = executor.submit(() -> pair.dispatch(
                BoundedCloseDispatcher.closeRequest(
                        () -> {
                            firstCloses.incrementAndGet();
                            throwCloseFailure(firstFailure);
                        },
                        "procwright-blocking-first-close-",
                        failureHandler),
                BoundedCloseDispatcher.closeRequest(
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

    private static void awaitUninterruptibly(CountDownLatch latch) {
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

    private static void throwCloseFailure(Throwable failure) throws IOException {
        if (failure instanceof IOException ioException) {
            throw ioException;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw (Error) failure;
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

    private static final class FailingCloseInputStream extends InputStream {

        private final IOException failure;

        private FailingCloseInputStream(IOException failure) {
            this.failure = failure;
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() throws IOException {
            throw failure;
        }
    }

    private static final class CloseSignalInputStream extends InputStream {

        private final CountDownLatch closed;

        private CloseSignalInputStream(CountDownLatch closed) {
            this.closed = closed;
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closed.countDown();
        }
    }

    private static final class BlockingFailingCloseInputStream extends InputStream {

        private final String name;
        private final IOException failure;
        private final AtomicInteger closeCalls;
        private final List<String> closeOrder;
        private final CountDownLatch closeStarted;
        private final CountDownLatch releaseClose;

        private BlockingFailingCloseInputStream(
                String name,
                IOException failure,
                AtomicInteger closeCalls,
                List<String> closeOrder,
                CountDownLatch closeStarted,
                CountDownLatch releaseClose) {
            this.name = name;
            this.failure = failure;
            this.closeCalls = closeCalls;
            this.closeOrder = closeOrder;
            this.closeStarted = closeStarted;
            this.releaseClose = releaseClose;
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() throws IOException {
            closeCalls.incrementAndGet();
            closeOrder.add(name);
            closeStarted.countDown();
            awaitUninterruptibly(releaseClose);
            throw failure;
        }
    }

    private static final class RecordingCloseInputStream extends InputStream {

        private final String name;
        private final AtomicInteger closeCalls;
        private final List<String> closeOrder;
        private final CountDownLatch closed;

        private RecordingCloseInputStream(
                String name, AtomicInteger closeCalls, List<String> closeOrder, CountDownLatch closed) {
            this.name = name;
            this.closeCalls = closeCalls;
            this.closeOrder = closeOrder;
            this.closed = closed;
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            closeOrder.add(name);
            closed.countDown();
        }
    }

    private static final class TrackingCloseable implements java.io.Closeable {

        private final int ordinal;
        private final List<Integer> closeOrder;
        private final CountDownLatch acceptedClosed;
        private final CountDownLatch started;
        private final CountDownLatch release;
        private final AtomicInteger closeCalls = new AtomicInteger();

        private TrackingCloseable(
                int ordinal,
                List<Integer> closeOrder,
                CountDownLatch acceptedClosed,
                CountDownLatch started,
                CountDownLatch release) {
            this.ordinal = ordinal;
            this.closeOrder = closeOrder;
            this.acceptedClosed = acceptedClosed;
            this.started = started;
            this.release = release;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            closeOrder.add(ordinal);
            if (started != null) {
                started.countDown();
            }
            if (release != null) {
                awaitUninterruptibly(release);
            }
            acceptedClosed.countDown();
        }

        private int closeCalls() {
            return closeCalls.get();
        }
    }
}
