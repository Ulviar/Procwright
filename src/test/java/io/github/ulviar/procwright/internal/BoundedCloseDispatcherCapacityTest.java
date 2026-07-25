/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static io.github.ulviar.procwright.internal.BoundedCloseDispatcherTestAccess.dispatch;
import static io.github.ulviar.procwright.internal.BoundedCloseDispatcherTestSupport.awaitUninterruptibly;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedCloseDispatcherTestSupport.CloseSignalInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class BoundedCloseDispatcherCapacityTest {

    @Test
    void validatesCapacityConfiguration() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedCloseDispatcher(0, 1));
        assertThrows(IllegalArgumentException.class, () -> new BoundedCloseDispatcher(1, 0));
        assertThrows(
                IllegalArgumentException.class, () -> new BoundedCloseDispatcher(Integer.MAX_VALUE, Integer.MAX_VALUE));
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 1);
        assertThrows(IllegalArgumentException.class, () -> dispatcher.reserve(0));
        assertThrows(IllegalArgumentException.class, () -> dispatcher.reserve(3));
    }

    @Test
    void admissionIsAtomicBoundedAndRejectionLeavesCleanupWithTheCaller() throws Exception {
        BoundedFailureReporter reporter = new BoundedFailureReporter(1, 3);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2, Threading::start, reporter);
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
            dispatch(
                    dispatcher.reserve(1),
                    request,
                    "procwright-bounded-close-",
                    failure -> {},
                    acceptedSettled::countDown);
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
                (name, task) -> {
                    int ordinal = starts.getAndIncrement();
                    if (ordinal == 0) {
                        firstStarterEntered.countDown();
                        awaitUninterruptibly(releaseFirstStarter);
                    }
                    Thread worker = new Thread(task, name + ordinal);
                    worker.setDaemon(true);
                    worker.start();
                },
                reporter);
        ExecutorService firstCaller = Executors.newSingleThreadExecutor();
        Future<?> firstDispatch = firstCaller.submit(() -> dispatch(
                dispatcher.reserve(1),
                allClosed::countDown,
                "procwright-blocked-first-starter-",
                ignored -> {},
                allSettled::countDown));
        try {
            assertTrue(firstStarterEntered.await(1, TimeUnit.SECONDS));

            dispatch(
                    dispatcher.reserve(1),
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

            dispatch(
                    dispatcher.reserve(1),
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
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 1, (name, task) -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            closeThread.set(thread);
            thread.start();
        });
        CountDownLatch closed = new CountDownLatch(1);

        BoundedCloseDispatcher.Reservation pair = dispatcher.reserve(2);

        assertEquals(2, dispatcher.outstandingCount());
        assertThrows(java.util.concurrent.RejectedExecutionException.class, () -> dispatcher.reserve(1));
        dispatch(pair, new CloseSignalInputStream(closed), "procwright-partial-reservation-", failure -> {});
        pair.release();
        pair.release();
        assertTrue(closed.await(1, TimeUnit.SECONDS));
        closeThread.get().join(TimeUnit.SECONDS.toMillis(1));
        assertFalse(closeThread.get().isAlive());
        assertThrows(
                IllegalStateException.class,
                () -> dispatch(pair, () -> {}, "procwright-double-dispatch-", failure -> {}));
        assertEquals(0, dispatcher.outstandingCount());
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
