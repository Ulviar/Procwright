/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class ProcessIoResourcesTest {

    @Test
    void acquiresEveryProcessStreamExactlyOnceBeforeReturning() {
        TrackingProcess process = new TrackingProcess();
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(3, 3, 6);

        ProcessIoResources resources = ProcessIoResources.acquire(process, dispatcher);

        assertSame(process.stdin, resources.stdin().stream());
        assertSame(process.stdout, resources.stdout().stream());
        assertSame(process.stderr, resources.stderr().stream());
        assertEquals(1, process.stdinGets.get());
        assertEquals(1, process.stdoutGets.get());
        assertEquals(1, process.stderrGets.get());
        resources.closeAllAsync(ignored -> {});
    }

    @Test
    void capacityExhaustionFailsBeforeAnyStreamIsObserved() {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2, 3);
        BoundedCloseDispatcher.Reservation occupied = dispatcher.reserve(3);
        TrackingProcess process = new TrackingProcess();

        assertThrows(RejectedExecutionException.class, () -> ProcessIoResources.acquire(process, dispatcher));

        assertEquals(0, process.stdinGets.get());
        assertEquals(0, process.stdoutGets.get());
        assertEquals(0, process.stderrGets.get());
        assertFalse(process.isAlive());
        occupied.release();
    }

    @Test
    void closeReservationReleaseFailureStillReleasesEveryPermitAndCleansTheProcess() throws Exception {
        IllegalStateException primary = new IllegalStateException("publication reservation failed");
        OutOfMemoryError releaseFailure = new OutOfMemoryError("first close permit release failed");
        AtomicInteger permitReleases = new AtomicInteger();
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(3, 3, 6);
        BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(
                3, BoundedLifecyclePublisher.StartWitness::start, java.util.ArrayDeque::new, (point, ordinal) -> {
                    if (point == BoundedLifecyclePublisher.FailurePoint.BEFORE_PERMIT_DEQUE_ALLOCATION) {
                        throw primary;
                    }
                });
        TrackingProcess process = new TrackingProcess();
        ProcessIoResources.ConstructionRollback rollback = new ProcessIoResources.ConstructionRollback() {
            @Override
            public void release(BoundedCloseDispatcher.Reservation reservation) {
                reservation.release(permit -> {
                    permit.release();
                    if (permitReleases.incrementAndGet() == 1) {
                        throw releaseFailure;
                    }
                });
            }
        };

        IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> ProcessIoResources.acquire(process, dispatcher, publisher, ignored -> {}, point -> {}, rollback));

        assertSame(primary, actual);
        assertEquals(3, permitReleases.get());
        assertFalse(process.isAlive());
        assertEquals(0, process.stdinGets.get());
        assertEquals(0, process.stdoutGets.get());
        assertEquals(0, process.stderrGets.get());
        assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
        assertSuppressedInOrder(primary, releaseFailure);
    }

    @Test
    void everyPartialAcquisitionFailureRollsBackStableResourcesByIdentity() throws Exception {
        for (int failedOrdinal = 1; failedOrdinal <= 3; failedOrdinal++) {
            for (boolean fatal : new boolean[] {false, true}) {
                Throwable expected = fatal
                        ? new AssertionError("getter " + failedOrdinal)
                        : new IllegalStateException("getter " + failedOrdinal);
                TrackingProcess process = new TrackingProcess(failedOrdinal, expected);
                BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(3, 3, 6);

                Throwable actual =
                        assertThrows(expected.getClass(), () -> ProcessIoResources.acquire(process, dispatcher));

                assertSame(expected, actual);
                assertFalse(process.isAlive());
                assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
                assertEquals(failedOrdinal >= 2 ? 1 : 0, process.stdin.closeCalls.get());
                assertEquals(failedOrdinal >= 3 ? 1 : 0, process.stdout.closeCalls.get());
                assertEquals(0, process.stderr.closeCalls.get());
                assertEquals(failedOrdinal >= 1 ? 1 : 0, process.stdinGets.get());
                assertEquals(failedOrdinal >= 2 ? 1 : 0, process.stdoutGets.get());
                assertEquals(failedOrdinal >= 3 ? 1 : 0, process.stderrGets.get());
            }
        }
    }

    @Test
    void everyInjectedConstructionFailureRecoversAllCapacityAndClosesEveryObservedStream() throws Exception {
        for (ProcessIoResources.ConstructionPoint failedPoint : ProcessIoResources.ConstructionPoint.values()) {
            OutOfMemoryError expected = new OutOfMemoryError("injected at " + failedPoint);
            TrackingProcess process = new TrackingProcess();
            BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(3, 3, 6);
            BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(3);

            OutOfMemoryError actual = assertThrows(
                    OutOfMemoryError.class,
                    () -> ProcessIoResources.acquire(process, dispatcher, publisher, ignored -> {}, point -> {
                        if (point == failedPoint) {
                            throw expected;
                        }
                    }));

            assertSame(expected, actual, failedPoint.toString());
            assertFalse(process.isAlive(), failedPoint.toString());
            assertTrue(
                    eventually(() -> dispatcher.outstandingCount() == 0 && publisher.ownerCount() == 0),
                    failedPoint.toString());
            assertEquals(process.stdinGets.get(), process.stdin.closeCalls.get(), failedPoint.toString());
            assertEquals(process.stdoutGets.get(), process.stdout.closeCalls.get(), failedPoint.toString());
            assertEquals(process.stderrGets.get(), process.stderr.closeCalls.get(), failedPoint.toString());
            assertTrue(process.stdinGets.get() <= 1, failedPoint.toString());
            assertTrue(process.stdoutGets.get() <= 1, failedPoint.toString());
            assertTrue(process.stderrGets.get() <= 1, failedPoint.toString());
        }
    }

    @Test
    void rollbackContinuesAfterEveryReservationResourceAndCleanupFailure() throws Exception {
        AssertionError primary = new AssertionError("construction failed");
        IllegalStateException processCleanupFailure = new IllegalStateException("process cleanup failed");
        OutOfMemoryError closeReservationFailure = new OutOfMemoryError("close reservation release failed");
        IllegalArgumentException publicationReservationFailure =
                new IllegalArgumentException("publication reservation release failed");
        OutOfMemoryError resourceRollbackFailure = new OutOfMemoryError("resource rollback failed");
        IllegalStateException rawStreamRollbackFailure = new IllegalStateException("raw stream rollback failed");
        TrackingProcess process = new TrackingProcess();
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(3, 3, 6);
        BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(3);
        AtomicInteger resourceRollbacks = new AtomicInteger();
        ProcessIoResources.ConstructionRollback rollback = new ProcessIoResources.ConstructionRollback() {
            @Override
            public void cleanupProcess(Process target) {
                ProcessIoResources.ConstructionRollback.super.cleanupProcess(target);
                throw processCleanupFailure;
            }

            @Override
            public void release(BoundedCloseDispatcher.Reservation reservation) {
                ProcessIoResources.ConstructionRollback.super.release(reservation);
                throw closeReservationFailure;
            }

            @Override
            public void release(BoundedLifecyclePublisher.Reservation reservation) {
                ProcessIoResources.ConstructionRollback.super.release(reservation);
                throw publicationReservationFailure;
            }

            @Override
            public void rollback(ProcessIoResources.Resource<?> resource, Throwable primaryFailure) {
                ProcessIoResources.ConstructionRollback.super.rollback(resource, primaryFailure);
                if (resourceRollbacks.incrementAndGet() == 1) {
                    throw resourceRollbackFailure;
                }
            }

            @Override
            public void closeInline(BoundedCloseDispatcher.Permit permit, java.io.Closeable stream) throws IOException {
                ProcessIoResources.ConstructionRollback.super.closeInline(permit, stream);
                throw rawStreamRollbackFailure;
            }
        };

        AssertionError actual = assertThrows(
                AssertionError.class,
                () -> ProcessIoResources.acquire(
                        process,
                        dispatcher,
                        publisher,
                        ignored -> {},
                        point -> {
                            if (point == ProcessIoResources.ConstructionPoint.AFTER_STDERR_STREAM_ACQUISITION) {
                                throw primary;
                            }
                        },
                        rollback));

        assertSame(primary, actual);
        assertFalse(process.isAlive());
        assertEquals(1, process.stdin.closeCalls.get());
        assertEquals(1, process.stdout.closeCalls.get());
        assertEquals(1, process.stderr.closeCalls.get());
        assertTrue(eventually(() -> dispatcher.outstandingCount() == 0 && publisher.ownerCount() == 0));
        assertSuppressedInOrder(
                primary,
                processCleanupFailure,
                closeReservationFailure,
                publicationReservationFailure,
                resourceRollbackFailure,
                rawStreamRollbackFailure);
    }

    @Test
    void partialAcquisitionAttachesEveryRollbackFailureOnceWithoutReplacingFatalIdentity() throws Exception {
        for (Throwable acquisitionFailure : java.util.List.of(
                new IllegalStateException("stderr getter failed"), new AssertionError("stderr getter failed"))) {
            IOException stdinCloseFailure = new IOException("stdin rollback close failed");
            AssertionError stdoutCloseFailure = new AssertionError("stdout rollback close failed");
            RollbackFailureProcess process =
                    new RollbackFailureProcess(acquisitionFailure, stdinCloseFailure, stdoutCloseFailure);
            BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 1, 3);

            Throwable actual =
                    assertThrows(acquisitionFailure.getClass(), () -> ProcessIoResources.acquire(process, dispatcher));

            assertSame(acquisitionFailure, actual);
            assertTrue(process.stdin.closed.await(1, TimeUnit.SECONDS));
            assertTrue(process.stdout.closed.await(1, TimeUnit.SECONDS));
            assertTrue(eventually(() -> actual.getSuppressed().length == 2));
            assertEquals(
                    1,
                    java.util.Arrays.stream(actual.getSuppressed())
                            .filter(failure -> failure == stdinCloseFailure)
                            .count());
            assertEquals(
                    1,
                    java.util.Arrays.stream(actual.getSuppressed())
                            .filter(failure -> failure == stdoutCloseFailure)
                            .count());
            assertEquals(1, process.stdin.closeCalls.get());
            assertEquals(1, process.stdout.closeCalls.get());
            assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
        }
    }

    @Test
    void asynchronousOutputCloseDoesNotWaitForAnActiveReadAndReportsIoFailure() throws Exception {
        BlockingReadInputStream stdout = new BlockingReadInputStream(new IOException("close failed"));
        TrackingProcess process = new TrackingProcess(stdout, new TrackingInputStream());
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 3, 4);
        ProcessIoResources resources = ProcessIoResources.acquire(process, dispatcher);
        AtomicReference<Throwable> closeFailure = new AtomicReference<>();
        CountDownLatch closeFailureReported = new CountDownLatch(1);
        Thread reader = new Thread(() -> {
            try {
                resources.stdout().stream().read();
            } catch (IOException ignored) {
                // The fixture owns the expected read/close interaction.
            }
        });
        reader.setDaemon(true);
        reader.start();
        assertTrue(stdout.readStarted.await(1, TimeUnit.SECONDS));

        long started = System.nanoTime();
        resources.stdout().closeAsync("test-output-close-", failure -> {
            closeFailure.set(failure);
            closeFailureReported.countDown();
        });
        long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

        assertTrue(elapsedMillis < 250, "dispatch must not wait for the stream monitor");
        stdout.releaseRead.countDown();
        assertTrue(stdout.closeCompleted.await(1, TimeUnit.SECONDS));
        resources.stdout().closeCompletion().get(1, TimeUnit.SECONDS);
        assertTrue(closeFailureReported.await(1, TimeUnit.SECONDS));
        reader.join(1_000);
        assertSame(stdout.closeFailure, closeFailure.get());
        assertEquals(1, stdout.closeCalls.get());
        resources.closeAllAsync(ignored -> {});
    }

    @Test
    void closeAllDispatchesEveryOwnedStreamWhenAnyStarterFails() throws Exception {
        for (int failedOrdinal = 1; failedOrdinal <= 3; failedOrdinal++) {
            int expectedFailedOrdinal = failedOrdinal;
            for (Throwable expected : java.util.List.of(
                    new IllegalStateException("starter " + failedOrdinal + " failed"),
                    new AssertionError("starter " + failedOrdinal + " failed"))) {
                AtomicInteger starts = new AtomicInteger();
                BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2, 3, (name, task) -> {
                    if (starts.incrementAndGet() == expectedFailedOrdinal) {
                        throwFailure(expected);
                    }
                    return Threading.start(name, task);
                });
                TrackingProcess process = new TrackingProcess();
                ProcessIoResources resources = ProcessIoResources.acquire(process, dispatcher);
                AtomicReference<Throwable> reported = new AtomicReference<>();
                CountDownLatch failureReported = new CountDownLatch(1);

                Throwable actual = captureFailure(() -> resources.closeAllAsync(failure -> {
                    reported.compareAndSet(null, failure);
                    failureReported.countDown();
                }));

                if (actual != null) {
                    assertSame(expected, actual);
                }
                assertTrue(failureReported.await(1, TimeUnit.SECONDS));
                assertSame(expected, reported.get());
                assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
                assertEquals(1, process.stdin.closeCalls.get(), "stdin close at starter " + failedOrdinal);
                assertEquals(1, process.stdout.closeCalls.get(), "stdout close at starter " + failedOrdinal);
                assertEquals(1, process.stderr.closeCalls.get(), "stderr close at starter " + failedOrdinal);
            }
        }
    }

    @Test
    void acceptedFallbackCloseKeepsResourceAndAwaitClosePendingUntilPhysicalSettlement() throws Exception {
        IllegalStateException startFailure = new IllegalStateException("stdout close starter failed");
        IOException closeFailure = new IOException("stdout fallback close failed");
        BlockingCloseInputStream stdout = new BlockingCloseInputStream(closeFailure);
        TrackingProcess process = new TrackingProcess(stdout, new TrackingInputStream());
        AtomicInteger starts = new AtomicInteger();
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 1, 3, (name, task) -> {
            if (starts.getAndIncrement() == 0) {
                throw startFailure;
            }
            return Threading.start(name, task);
        });
        ProcessIoResources resources = ProcessIoResources.acquire(process, dispatcher);
        ExecutorService waiter = Executors.newSingleThreadExecutor();
        AtomicBoolean completeFailureGraphObserved = new AtomicBoolean();
        try {
            IllegalStateException thrown = assertThrows(
                    IllegalStateException.class,
                    () -> resources.stdout().closeAsync("procwright-blocked-fallback-output-", ignored -> {}));
            assertSame(startFailure, thrown);
            assertTrue(stdout.closeEntered.await(1, TimeUnit.SECONDS));

            resources.closeAllAsync(ignored -> {});
            resources
                    .stdout()
                    .closeCompletion()
                    .thenRun(() ->
                            completeFailureGraphObserved.set(resources.stdout().closeResult() == startFailure
                                    && java.util.List.of(closeFailure)
                                            .equals(java.util.List.of(startFailure.getSuppressed()))));
            Future<Throwable> awaitClose = waiter.submit(() -> resources.awaitClose(Duration.ofSeconds(2)));

            assertFalse(resources.stdout().closeCompletion().isDone());
            assertFalse(awaitClose.isDone());

            stdout.releaseClose.countDown();
            assertSame(startFailure, awaitClose.get(1, TimeUnit.SECONDS));
            assertSame(startFailure, resources.stdout().closeResult());
            assertEquals(java.util.List.of(closeFailure), java.util.List.of(startFailure.getSuppressed()));
            assertTrue(completeFailureGraphObserved.get());
            assertEquals(1, stdout.closeCalls.get());
            assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
        } finally {
            stdout.releaseClose.countDown();
            resources.closeAllAsync(ignored -> {});
            waiter.shutdownNow();
            assertTrue(waiter.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void blockedFailureCallbackCannotRetainResourceCompletion() throws Exception {
        AssertionError closeFailure = new AssertionError("stdout close failed");
        TrackingInputStream stdout = new TrackingInputStream() {
            @Override
            public void close() {
                closeCalls.incrementAndGet();
                throw closeFailure;
            }
        };
        TrackingProcess process = new TrackingProcess(stdout, new TrackingInputStream());
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2, 3);
        ProcessIoResources resources = ProcessIoResources.acquire(process, dispatcher);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        try {
            resources.stdout().closeAsync("procwright-blocked-resource-report-", failure -> {
                assertSame(closeFailure, failure);
                callbackEntered.countDown();
                BlockingReadInputStream.awaitUninterruptibly(releaseCallback);
            });

            assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));
            resources.stdout().closeCompletion().get(1, TimeUnit.SECONDS);
            assertSame(closeFailure, resources.stdout().closeResult());
            assertEquals(1, stdout.closeCalls.get());
        } finally {
            releaseCallback.countDown();
            resources.closeAllAsync(ignored -> {});
            assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
        }
    }

    @Test
    void secondFallbackPreservesIndependentFailureGraphsAndProgressPastBlockedFirstCompletion() throws Exception {
        IllegalStateException firstStartFailure = new IllegalStateException("first starter failed");
        AssertionError secondStartFailure = new AssertionError("second starter failed");
        IOException firstCloseFailure = new IOException("first physical close failed");
        IOException secondCloseFailure = new IOException("second physical close failed");
        TrackingInputStream stdout = failingInput(firstCloseFailure);
        TrackingInputStream stderr = failingInput(secondCloseFailure);
        AtomicInteger starts = new AtomicInteger();
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2, 3, (name, task) -> {
            throwFailure(starts.getAndIncrement() == 0 ? firstStartFailure : secondStartFailure);
            throw new AssertionError("unreachable");
        });
        BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(3);
        TrackingProcess process = new TrackingProcess(stdout, stderr);
        ProcessIoResources resources = ProcessIoResources.acquire(process, dispatcher, publisher);
        CompletableFuture<Void> escape = new CompletableFuture<>();
        CompletableFuture<Void> secondCompletion = resources.stderr().closeCompletion();
        CountDownLatch firstContinuationEntered = new CountDownLatch(1);
        AtomicReference<Throwable> firstReported = new AtomicReference<>();
        AtomicReference<Throwable> secondReported = new AtomicReference<>();
        AtomicInteger firstReports = new AtomicInteger();
        AtomicInteger secondReports = new AtomicInteger();
        CountDownLatch reports = new CountDownLatch(2);
        CompletableFuture<Void> firstContinuation = resources
                .stdout()
                .closeCompletion()
                .thenRun(() -> {
                    firstContinuationEntered.countDown();
                    CompletableFuture.anyOf(secondCompletion, escape).join();
                });
        try {
            Throwable firstThrown = assertThrows(
                    IllegalStateException.class,
                    () -> resources.stdout().closeAsync("procwright-first-fallback-close-", failure -> {
                        firstReported.set(failure);
                        firstReports.incrementAndGet();
                        reports.countDown();
                    }));
            assertSame(firstStartFailure, firstThrown);
            assertTrue(firstContinuationEntered.await(1, TimeUnit.SECONDS));

            Throwable secondThrown = assertThrows(
                    AssertionError.class,
                    () -> resources.stderr().closeAsync("procwright-second-fallback-close-", failure -> {
                        secondReported.set(failure);
                        secondReports.incrementAndGet();
                        reports.countDown();
                    }));

            assertSame(secondStartFailure, secondThrown);
            secondCompletion.get(1, TimeUnit.SECONDS);
            firstContinuation.get(1, TimeUnit.SECONDS);
            assertTrue(reports.await(1, TimeUnit.SECONDS));
            assertSame(firstStartFailure, resources.stdout().closeResult());
            assertSame(secondStartFailure, resources.stderr().closeResult());
            assertSame(firstStartFailure, firstReported.get());
            assertSame(secondStartFailure, secondReported.get());
            assertEquals(java.util.List.of(firstCloseFailure), java.util.List.of(firstStartFailure.getSuppressed()));
            assertEquals(java.util.List.of(secondCloseFailure), java.util.List.of(secondStartFailure.getSuppressed()));
            assertEquals(0, firstCloseFailure.getSuppressed().length);
            assertEquals(0, secondCloseFailure.getSuppressed().length);
            assertEquals(1, firstReports.get());
            assertEquals(1, secondReports.get());
            assertEquals(1, process.stdout.closeCalls.get());
            assertEquals(1, process.stderr.closeCalls.get());
            resources.stdin().closeInline();
            resources.stdin().closeCompletion().get(1, TimeUnit.SECONDS);
            assertTrue(eventually(() -> dispatcher.outstandingCount() == 0 && publisher.ownerCount() == 0));
            assertEquals(0, dispatcher.activeCount());
            assertEquals(0, dispatcher.pendingCount());
        } finally {
            escape.complete(null);
            captureFailure(() -> resources.closeAllAsync(ignored -> {}));
            assertTrue(eventually(() -> dispatcher.outstandingCount() == 0 && publisher.ownerCount() == 0));
        }
    }

    @Test
    void blockedCompletionOwnerAppliesExactBackpressureAndRecoversWithoutRetainingCloseCapacity() throws Exception {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2, 3);
        BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(3);
        TrackingProcess process = new TrackingProcess();
        ProcessIoResources resources = ProcessIoResources.acquire(process, dispatcher, publisher);
        CountDownLatch continuationEntered = new CountDownLatch(1);
        CountDownLatch releaseContinuation = new CountDownLatch(1);
        CompletableFuture<Void> continuation = resources
                .stdout()
                .closeCompletion()
                .thenRun(() -> {
                    continuationEntered.countDown();
                    BlockingReadInputStream.awaitUninterruptibly(releaseContinuation);
                });
        try {
            assertEquals(3, publisher.ownerCount());
            resources.stdout().closeAsync("procwright-bounded-completion-owner-", ignored -> {});
            assertTrue(continuationEntered.await(1, TimeUnit.SECONDS));

            assertEquals(2, dispatcher.outstandingCount());
            assertEquals(3, publisher.ownerCount());
            BoundedCloseDispatcher.Reservation physicalCapacity = dispatcher.reserve(1);
            physicalCapacity.release();
            assertThrows(RejectedExecutionException.class, () -> publisher.reserve(1));
            assertEquals(2, dispatcher.outstandingCount());

            releaseContinuation.countDown();
            continuation.get(1, TimeUnit.SECONDS);
            assertTrue(eventually(() -> publisher.ownerCount() == 2));
            BoundedLifecyclePublisher.Reservation recovered = publisher.reserve(1);
            recovered.release();
        } finally {
            releaseContinuation.countDown();
            resources.closeAllAsync(ignored -> {});
            assertTrue(eventually(() -> dispatcher.outstandingCount() == 0 && publisher.ownerCount() == 0));
        }
    }

    private static void throwFailure(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        throw (Error) failure;
    }

    private static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static void assertSuppressedInOrder(Throwable primary, Throwable... expected) {
        Throwable[] actual = primary.getSuppressed();
        assertEquals(expected.length, actual.length);
        for (int index = 0; index < expected.length; index++) {
            assertSame(expected[index], actual[index], "suppressed failure " + index);
        }
    }

    private static TrackingInputStream failingInput(IOException failure) {
        return new TrackingInputStream() {
            @Override
            public void close() throws IOException {
                closeCalls.incrementAndGet();
                throw failure;
            }
        };
    }

    private static boolean eventually(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (!condition.getAsBoolean()) {
            if (deadline - System.nanoTime() <= 0) {
                return false;
            }
            Thread.sleep(5);
        }
        return true;
    }

    private static class TrackingProcess extends Process {

        final TrackingOutputStream stdin = new TrackingOutputStream();
        final TrackingInputStream stdout;
        final TrackingInputStream stderr;
        final AtomicInteger stdinGets = new AtomicInteger();
        final AtomicInteger stdoutGets = new AtomicInteger();
        final AtomicInteger stderrGets = new AtomicInteger();
        private final int failedOrdinal;
        private final Throwable getterFailure;
        private final AtomicBoolean alive = new AtomicBoolean(true);

        TrackingProcess() {
            this(new TrackingInputStream(), new TrackingInputStream());
        }

        TrackingProcess(TrackingInputStream stdout, TrackingInputStream stderr) {
            this(0, null, stdout, stderr);
        }

        TrackingProcess(int failedOrdinal, Throwable getterFailure) {
            this(failedOrdinal, getterFailure, new TrackingInputStream(), new TrackingInputStream());
        }

        private TrackingProcess(
                int failedOrdinal, Throwable getterFailure, TrackingInputStream stdout, TrackingInputStream stderr) {
            this.failedOrdinal = failedOrdinal;
            this.getterFailure = getterFailure;
            this.stdout = stdout;
            this.stderr = stderr;
        }

        @Override
        public OutputStream getOutputStream() {
            failGetter(stdinGets.incrementAndGet());
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            failGetter(stdoutGets.incrementAndGet() + 1);
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            failGetter(stderrGets.incrementAndGet() + 2);
            return stderr;
        }

        private void failGetter(int ordinal) {
            if (ordinal != failedOrdinal) {
                return;
            }
            if (getterFailure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw (Error) getterFailure;
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive.get();
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("alive");
            }
            return 143;
        }

        @Override
        public void destroy() {
            alive.set(false);
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public ProcessHandle toHandle() {
            throw new UnsupportedOperationException("test process has no handle");
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }

    private static final class TrackingOutputStream extends OutputStream {

        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public void write(int value) {}

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }
    }

    private static class TrackingInputStream extends InputStream {

        final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() throws IOException {
            closeCalls.incrementAndGet();
        }
    }

    private static final class BlockingCloseInputStream extends TrackingInputStream {

        private final IOException failure;
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseClose = new CountDownLatch(1);

        private BlockingCloseInputStream(IOException failure) {
            this.failure = failure;
        }

        @Override
        public void close() throws IOException {
            closeCalls.incrementAndGet();
            closeEntered.countDown();
            BlockingReadInputStream.awaitUninterruptibly(releaseClose);
            throw failure;
        }
    }

    private static final class BlockingReadInputStream extends TrackingInputStream {

        private final IOException closeFailure;
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch releaseRead = new CountDownLatch(1);
        private final CountDownLatch closeCompleted = new CountDownLatch(1);

        private BlockingReadInputStream(IOException closeFailure) {
            this.closeFailure = closeFailure;
        }

        @Override
        public synchronized int read() {
            readStarted.countDown();
            awaitUninterruptibly(releaseRead);
            return -1;
        }

        @Override
        public synchronized void close() throws IOException {
            closeCalls.incrementAndGet();
            closeCompleted.countDown();
            throw closeFailure;
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
    }

    private static final class RollbackFailureProcess extends Process {

        private final Throwable acquisitionFailure;
        private final FailingCloseOutputStream stdin;
        private final FailingCloseInputStream stdout;
        private final AtomicBoolean alive = new AtomicBoolean(true);

        private RollbackFailureProcess(
                Throwable acquisitionFailure, IOException stdinCloseFailure, AssertionError stdoutCloseFailure) {
            this.acquisitionFailure = acquisitionFailure;
            stdin = new FailingCloseOutputStream(stdinCloseFailure);
            stdout = new FailingCloseInputStream(stdoutCloseFailure);
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            if (acquisitionFailure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw (Error) acquisitionFailure;
        }

        @Override
        public int waitFor() {
            return 143;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive.get();
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("alive");
            }
            return 143;
        }

        @Override
        public void destroy() {
            alive.set(false);
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public ProcessHandle toHandle() {
            throw new UnsupportedOperationException("test process has no handle");
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }

    private static final class FailingCloseOutputStream extends OutputStream {

        private final IOException failure;
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);

        private FailingCloseOutputStream(IOException failure) {
            this.failure = failure;
        }

        @Override
        public void write(int value) {}

        @Override
        public void close() throws IOException {
            closeCalls.incrementAndGet();
            closed.countDown();
            throw failure;
        }
    }

    private static final class FailingCloseInputStream extends InputStream {

        private final AssertionError failure;
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);

        private FailingCloseInputStream(AssertionError failure) {
            this.failure = failure;
        }

        @Override
        public int read() {
            return -1;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            closed.countDown();
            throw failure;
        }
    }
}
