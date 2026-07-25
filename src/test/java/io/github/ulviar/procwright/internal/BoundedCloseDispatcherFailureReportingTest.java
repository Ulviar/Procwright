/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static io.github.ulviar.procwright.internal.BoundedCloseDispatcherTestAccess.dispatch;
import static io.github.ulviar.procwright.internal.BoundedCloseDispatcherTestSupport.awaitUninterruptibly;
import static io.github.ulviar.procwright.internal.BoundedCloseDispatcherTestSupport.throwCloseFailure;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedCloseDispatcherTestSupport.CloseSignalInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class BoundedCloseDispatcherFailureReportingTest {

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
        };
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, closeCount - 2, starter, reporter);
        try {
            for (int ordinal = 0; ordinal < closeCount; ordinal++) {
                AssertionError failure = new AssertionError("close " + ordinal);
                failures.add(failure);
                dispatch(
                        dispatcher.reserve(1),
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
            assertTrue(reporter.activeCount() <= 1);
            assertTrue(reporter.queuedCount() <= 2);
        } finally {
            releaseHandlers.countDown();
        }
    }

    @Test
    void capacityOneQueuesReservedClosesInFifoOrderAndAdvancesBeforeFailureCallbackReturns() throws Exception {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2);
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
            dispatch(
                    dispatcher.reserve(1),
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

            dispatch(
                    dispatcher.reserve(1),
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
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 1);

        try {
            dispatch(
                    dispatcher.reserve(1),
                    new FailingCloseInputStream(closeFailure),
                    "procwright-first-output-close-",
                    failure -> {
                        observedFailure.set(failure);
                        activeObservedByCallback.set(dispatcher.activeCount());
                        callbackEntered.countDown();
                        awaitUninterruptibly(releaseCallback);
                    });

            assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));
            assertSame(closeFailure, observedFailure.get());
            assertEquals(0, activeObservedByCallback.get(), "failure reporting must not retain close capacity");

            dispatch(
                    dispatcher.reserve(1),
                    new CloseSignalInputStream(secondClose),
                    "procwright-second-output-close-",
                    failure -> {});
            assertTrue(
                    secondClose.await(1, TimeUnit.SECONDS), "the next physical close must not wait for the callback");
        } finally {
            releaseCallback.countDown();
        }
    }

    @Test
    void everyPhysicalCloseFailureKindReleasesAccountingAndReportsTheOriginalFailureOnce() throws Exception {
        for (Throwable expected : List.of(
                new IOException("io failure"),
                new IllegalStateException("runtime failure"),
                new AssertionError("error failure"))) {
            BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 1);
            AtomicInteger reports = new AtomicInteger();
            AtomicReference<Throwable> observed = new AtomicReference<>();
            CountDownLatch reported = new CountDownLatch(1);

            dispatch(
                    dispatcher.reserve(1),
                    () -> throwCloseFailure(expected),
                    "procwright-failing-output-close-",
                    failure -> {
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
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 1, Threading::start, reporter);
        IOException closeFailure = new IOException("close failed");
        CountDownLatch reentrantClose = new CountDownLatch(1);
        CountDownLatch reentrantSettled = new CountDownLatch(1);
        CountDownLatch callbackFinished = new CountDownLatch(1);

        dispatch(
                dispatcher.reserve(1),
                new FailingCloseInputStream(closeFailure),
                "procwright-reentrant-failure-",
                failure -> {
                    assertSame(closeFailure, failure);
                    dispatch(
                            dispatcher.reserve(1),
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

    @Test
    void callbackFailureIsReportedWithoutMutatingTheCloseFailure() throws Exception {
        IOException closeFailure = new IOException("close failed");
        IllegalStateException callbackFailure = new IllegalStateException("callback failed");
        AtomicInteger callbackCalls = new AtomicInteger();
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        CountDownLatch callbackReturned = new CountDownLatch(1);
        CountDownLatch uncaughtReported = new CountDownLatch(1);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 1, (name, task) -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, failure) -> {
                uncaught.set(failure);
                uncaughtReported.countDown();
            });
            thread.start();
        });
        dispatch(
                dispatcher.reserve(1),
                new FailingCloseInputStream(closeFailure),
                "procwright-failed-close-",
                failure -> {
                    callbackCalls.incrementAndGet();
                    callbackReturned.countDown();
                    throw callbackFailure;
                });
        assertTrue(callbackReturned.await(1, TimeUnit.SECONDS));
        assertTrue(uncaughtReported.await(1, TimeUnit.SECONDS));

        assertEquals(1, callbackCalls.get());
        assertSame(closeFailure, uncaught.get().getCause());
        assertEquals(List.of(callbackFailure), List.of(uncaught.get().getSuppressed()));
        assertEquals(0, closeFailure.getSuppressed().length);
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
}
