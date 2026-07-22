/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class StreamListenerTaskOwnerTest {

    @Test
    void abnormalIdleOwnerExitReplacesAndCompletesAnAlreadyAcceptedDelivery() throws Exception {
        BoundedTaskRunner.Limiter limiter = new BoundedTaskRunner.Limiter(1);
        CountDownLatch firstIdleWait = new CountDownLatch(1);
        AtomicInteger waits = new AtomicInteger();
        IdleOwnerFailure idleOwnerFailure = new IdleOwnerFailure();
        FailureCapture failures = new FailureCapture();
        List<Thread> createdOwners = new CopyOnWriteArrayList<>();
        StreamListenerTaskOwner owner = new StreamListenerTaskOwner(
                monitor -> {
                    if (waits.getAndIncrement() == 0) {
                        firstIdleWait.countDown();
                        monitor.wait();
                        throw idleOwnerFailure;
                    }
                    monitor.wait();
                },
                (name, task) -> {
                    Thread thread = ownerThread(name, task, failures);
                    createdOwners.add(thread);
                    return thread;
                });
        Thread firstOwner;
        Thread replacement;
        try {
            firstOwner = invoke(owner, limiter, failures, Thread::currentThread);
            assertTrue(firstIdleWait.await(1, TimeUnit.SECONDS));

            replacement = invoke(owner, limiter, failures, Thread::currentThread);

            assertTrue(failures.awaitFirst(Duration.ofSeconds(1)));
            assertTrue(eventually(() -> !firstOwner.isAlive()));
            assertNotSame(firstOwner, replacement);
            assertEquals(1, limiter.availablePermits());
        } finally {
            owner.close();
        }
        assertTrue(owner.awaitStopped(Duration.ofSeconds(1)));
        assertEquals(2, createdOwners.size());
        assertSame(firstOwner, createdOwners.get(0));
        assertSame(replacement, createdOwners.get(1));
        failures.assertOnlyUncaught(firstOwner, idleOwnerFailure);
    }

    @Test
    void failedReplacementSettlesAcceptedDeliveryAndReleasesItsGlobalPermit() throws Exception {
        BoundedTaskRunner.Limiter limiter = new BoundedTaskRunner.Limiter(1);
        CountDownLatch firstIdleWait = new CountDownLatch(1);
        AtomicInteger waits = new AtomicInteger();
        AtomicInteger threadCreations = new AtomicInteger();
        SecurityException replacementFailure = new SecurityException("replacement owner denied");
        IdleOwnerFailure idleOwnerFailure = new IdleOwnerFailure();
        FailureCapture failures = new FailureCapture();
        List<Thread> createdOwners = new CopyOnWriteArrayList<>();
        StreamListenerTaskOwner owner = new StreamListenerTaskOwner(
                monitor -> {
                    if (waits.getAndIncrement() == 0) {
                        firstIdleWait.countDown();
                        monitor.wait();
                        throw idleOwnerFailure;
                    }
                    monitor.wait();
                },
                (name, task) -> {
                    if (threadCreations.incrementAndGet() > 1) {
                        throw replacementFailure;
                    }
                    Thread thread = ownerThread(name, task, failures);
                    createdOwners.add(thread);
                    return thread;
                });
        Thread firstOwner;
        try {
            firstOwner = invoke(owner, limiter, failures, Thread::currentThread);
            assertTrue(firstIdleWait.await(1, TimeUnit.SECONDS));

            ExecutionException rejected =
                    assertThrows(ExecutionException.class, () -> invoke(owner, limiter, failures, () -> null));

            assertSame(replacementFailure, rejected.getCause());
            assertTrue(failures.awaitFirst(Duration.ofSeconds(1)));
            assertTrue(eventually(() -> !firstOwner.isAlive()));
            assertTrue(eventually(() -> limiter.availablePermits() == 1));
            assertTrue(owner.awaitStopped(Duration.ofSeconds(1)));
        } finally {
            owner.close();
        }
        assertEquals(2, threadCreations.get());
        assertEquals(List.of(firstOwner), createdOwners);
        failures.assertOnlyUncaught(firstOwner, idleOwnerFailure);
    }

    @Test
    void closeAndSubmitRaceAlwaysSettlesAcceptedAdmissionExactlyOnce() throws Exception {
        for (int run = 0; run < 200; run++) {
            BoundedTaskRunner.Limiter limiter = new BoundedTaskRunner.Limiter(1);
            StreamListenerTaskOwner owner = new StreamListenerTaskOwner();
            FailureCapture failures = new FailureCapture();
            CountDownLatch start = new CountDownLatch(1);
            AtomicInteger deliveries = new AtomicInteger();
            ExecutorService racers = Executors.newFixedThreadPool(2);
            try {
                Future<Throwable> submission = racers.submit(() -> {
                    start.await();
                    try {
                        invoke(owner, limiter, failures, () -> {
                            deliveries.incrementAndGet();
                            return null;
                        });
                        return null;
                    } catch (Throwable failure) {
                        return failure;
                    }
                });
                Future<?> close = racers.submit(() -> {
                    start.await();
                    owner.close();
                    return null;
                });

                start.countDown();
                Throwable failure = submission.get(1, TimeUnit.SECONDS);
                close.get(1, TimeUnit.SECONDS);

                if (failure == null) {
                    assertEquals(1, deliveries.get());
                } else {
                    assertTrue(failure instanceof ExecutionException, failure::toString);
                    assertEquals(0, deliveries.get());
                }
                assertTrue(eventually(() -> limiter.availablePermits() == 1));
                assertTrue(owner.awaitStopped(Duration.ofSeconds(1)));
                failures.assertEmpty();
            } finally {
                owner.close();
                racers.shutdownNow();
                assertTrue(racers.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void thousandsOfChunksReuseOnlyTheirSessionOwnerAndNeverCrossSessionState() throws Exception {
        BoundedTaskRunner.Limiter limiter = new BoundedTaskRunner.Limiter(1);
        FailureCapture failures = new FailureCapture();
        ThreadLocal<String> callbackState = new ThreadLocal<>();
        InheritableThreadLocal<String> inherited = new InheritableThreadLocal<>();
        inherited.set("caller-state");
        StreamListenerTaskOwner first = new StreamListenerTaskOwner();
        StreamListenerTaskOwner second = new StreamListenerTaskOwner();
        Thread firstThread = null;
        try {
            for (int chunk = 0; chunk < 2_000; chunk++) {
                int index = chunk;
                Thread currentThread = invoke(first, limiter, failures, () -> {
                    if (index == 0) {
                        assertNull(callbackState.get());
                        assertNull(inherited.get(), "listener owner inherited session-creator state");
                        callbackState.set("first-session");
                    } else {
                        assertEquals("first-session", callbackState.get());
                    }
                    return Thread.currentThread();
                });
                if (firstThread == null) {
                    firstThread = currentThread;
                } else {
                    assertEquals(firstThread, currentThread);
                }
            }

            Thread secondThread = invoke(second, limiter, failures, () -> {
                assertNull(callbackState.get(), "listener ThreadLocal crossed stream-session ownership");
                assertNull(inherited.get(), "listener owner inherited session-creator state");
                return Thread.currentThread();
            });
            assertNotSame(firstThread, secondThread);
        } finally {
            inherited.remove();
            first.close();
            second.close();
        }
        assertTrue(first.awaitStopped(Duration.ofSeconds(1)));
        assertTrue(second.awaitStopped(Duration.ofSeconds(1)));
        assertEquals(1, limiter.availablePermits());
        failures.assertEmpty();
    }

    @Test
    void cancelledNonCooperativeListenerRetainsGlobalAdmissionUntilItActuallyReturns() throws Exception {
        BoundedTaskRunner.Limiter limiter = new BoundedTaskRunner.Limiter(1);
        BoundedTaskRunner.CancellationSignal cancellation = new BoundedTaskRunner.CancellationSignal();
        StreamListenerTaskOwner owner = new StreamListenerTaskOwner();
        FailureCapture failures = new FailureCapture();
        CountDownLatch started = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<?> running = caller.submit(() -> BoundedTaskRunner.runReportingLateFailure(
                    limiter,
                    "procwright-stream-affinity-test-",
                    Long.MAX_VALUE,
                    cancellation,
                    failures::recordLate,
                    owner,
                    () -> {
                        started.countDown();
                        awaitIgnoringInterrupts(release);
                        return null;
                    }));
            assertTrue(started.await(1, TimeUnit.SECONDS));

            cancellation.cancel();

            ExecutionException wrapper = assertThrows(ExecutionException.class, () -> running.get(1, TimeUnit.SECONDS));
            assertTrue(wrapper.getCause() instanceof BoundedTaskRunner.TaskCancelledException);
            assertEquals(0, limiter.availablePermits());
            owner.close();
            assertFalse(owner.awaitStopped(Duration.ofMillis(25)));
        } finally {
            release.countDown();
            owner.close();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(1, TimeUnit.SECONDS));
        }
        assertTrue(owner.awaitStopped(Duration.ofSeconds(1)));
        assertTrue(eventually(() -> limiter.availablePermits() == 1));
        failures.assertEmpty();
    }

    @Test
    void closeDrainsAnAlreadyAcceptedPendingDeliveryBeforeTheOwnerStops() throws Exception {
        StreamListenerTaskOwner owner = new StreamListenerTaskOwner();
        FailureCapture failures = new FailureCapture();
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch pendingRan = new CountDownLatch(1);
        try {
            owner.start(
                    "procwright-stream-pending-test-",
                    () -> {
                        firstStarted.countDown();
                        awaitIgnoringInterrupts(releaseFirst);
                    },
                    failures::recordRejection);
            assertTrue(firstStarted.await(1, TimeUnit.SECONDS));
            owner.start("procwright-stream-pending-test-", pendingRan::countDown, failures::recordRejection);

            owner.close();
            releaseFirst.countDown();

            assertTrue(pendingRan.await(1, TimeUnit.SECONDS));
            assertTrue(owner.awaitStopped(Duration.ofSeconds(1)));
            failures.assertEmpty();
        } finally {
            releaseFirst.countDown();
            owner.close();
        }
    }

    private static <T> T invoke(
            StreamListenerTaskOwner owner,
            BoundedTaskRunner.Limiter limiter,
            FailureCapture failures,
            BoundedTaskRunner.Task<T> task)
            throws Exception {
        return BoundedTaskRunner.runReportingLateFailure(
                limiter,
                "procwright-stream-affinity-test-",
                deadline(Duration.ofSeconds(1)),
                null,
                failures::recordLate,
                owner,
                task);
    }

    private static long deadline(Duration duration) {
        return System.nanoTime() + duration.toNanos();
    }

    private static boolean eventually(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (!condition.getAsBoolean() && deadline - System.nanoTime() > 0) {
            Thread.onSpinWait();
        }
        return condition.getAsBoolean();
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

    private static Thread ownerThread(String name, Runnable task, FailureCapture failures) {
        Thread thread = io.github.ulviar.procwright.internal.Threading.unstartedPlatformNonInheriting(name, task);
        thread.setUncaughtExceptionHandler(failures::recordUncaught);
        return thread;
    }

    private static final class FailureCapture {

        private final List<FailureEvent> events = new CopyOnWriteArrayList<>();
        private final CountDownLatch firstEvent = new CountDownLatch(1);

        private void recordUncaught(Thread thread, Throwable failure) {
            record(FailureChannel.UNCAUGHT, thread, failure);
        }

        private void recordLate(Thread thread, Throwable failure) {
            record(FailureChannel.LATE, thread, failure);
        }

        private void recordRejection(Throwable failure) {
            record(FailureChannel.REJECTION, Thread.currentThread(), failure);
        }

        private void record(FailureChannel channel, Thread thread, Throwable failure) {
            events.add(new FailureEvent(channel, thread, failure));
            firstEvent.countDown();
        }

        private boolean awaitFirst(Duration timeout) throws InterruptedException {
            return firstEvent.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
        }

        private void assertOnlyUncaught(Thread expectedThread, Throwable expectedFailure) {
            assertEquals(1, events.size(), () -> "captured failures: " + events);
            FailureEvent event = events.get(0);
            assertEquals(FailureChannel.UNCAUGHT, event.channel());
            assertSame(expectedThread, event.thread());
            assertSame(expectedFailure, event.failure());
        }

        private void assertEmpty() {
            assertTrue(events.isEmpty(), () -> "captured failures: " + events);
        }
    }

    private enum FailureChannel {
        UNCAUGHT,
        LATE,
        REJECTION
    }

    private record FailureEvent(FailureChannel channel, Thread thread, Throwable failure) {}

    private static final class IdleOwnerFailure extends RuntimeException {

        private static final long serialVersionUID = 1L;
    }
}
