/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
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
import java.util.function.BiConsumer;
import org.junit.jupiter.api.Test;

final class ProcessStreamResourceTest extends ProcessIoResourcesTestSupport {

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
    void blockedFailureGraphCannotHoldTheSharedCloseClaim() throws Exception {
        IOException physicalFailure = new IOException("stdout physical close failed");
        BlockingCauseError callbackFailure = new BlockingCauseError();
        TrackingInputStream stdout = failingInput(physicalFailure);
        TrackingProcess process = new TrackingProcess(stdout, new TrackingInputStream());
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 1, 3);
        ProcessIoResources resources = ProcessIoResources.acquire(process, dispatcher);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> stderrClose = null;
        try {
            resources
                    .stdout()
                    .closeOwnedAsync(
                            "procwright-blocked-failure-graph-",
                            failure -> {
                                throw callbackFailure;
                            },
                            () -> {});
            assertTrue(callbackFailure.causeAccessed.await(1, TimeUnit.SECONDS));

            stderrClose = executor.submit(
                    () -> resources.stderr().closeAsync("procwright-independent-stderr-close-", ignored -> {}));
            stderrClose.get(1, TimeUnit.SECONDS);
        } finally {
            callbackFailure.releaseCause.countDown();
            if (stderrClose != null) {
                stderrClose.get(1, TimeUnit.SECONDS);
            }
            resources.closeAllAsync(ignored -> {});
            assertSame(physicalFailure, resources.awaitClose(Duration.ofSeconds(1)));
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
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
            Threading.start(name, task);
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
            CompletableFuture<Void> completeFailureGraphObservation = resources
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
            completeFailureGraphObservation.get(1, TimeUnit.SECONDS);
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
    void reporterOwnerStartFailureCannotStrandMandatoryCloseSettlement() throws Exception {
        IOException physicalFailure = new IOException("stdout close failed");
        AssertionError failureCallbackFailure = new AssertionError("failure callback failed");
        IllegalStateException completionCallbackFailure = new IllegalStateException("completion callback failed");
        TrackingInputStream stdout = failingInput(physicalFailure);
        TrackingProcess process = new TrackingProcess(stdout, new TrackingInputStream());
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2, 3);
        BiConsumer<BoundedFailureReporter.FailureTarget, Throwable> failureReporter = reporterWhoseOwnerCannotStart();
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        CountDownLatch uncaughtReported = new CountDownLatch(1);
        BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(3, task -> {
            Thread owner = Threading.unstartedPlatformNonInheriting("procwright-test-close-publication-", task);
            owner.setUncaughtExceptionHandler((ignored, failure) -> {
                uncaught.compareAndSet(null, failure);
                uncaughtReported.countDown();
            });
            return owner;
        });
        ProcessIoResources resources =
                ProcessIoResources.acquire(process, dispatcher, publisher, ignored -> {}, failureReporter);

        resources
                .stdout()
                .closeOwnedAsync(
                        "procwright-test-owned-close-",
                        failure -> {
                            assertSame(physicalFailure, failure);
                            throw failureCallbackFailure;
                        },
                        () -> {
                            throw completionCallbackFailure;
                        });
        resources.stdin().closeInline();
        resources.stderr().closeInline();

        resources.stdout().closeCompletion().get(1, TimeUnit.SECONDS);
        assertSame(physicalFailure, resources.awaitClose(Duration.ofSeconds(1)));
        assertTrue(uncaughtReported.await(1, TimeUnit.SECONDS));
        assertSame(failureCallbackFailure, uncaught.get());
        assertSame(physicalFailure, resources.stdout().closeResult());
        assertEquals(java.util.List.of(failureCallbackFailure), java.util.List.of(physicalFailure.getSuppressed()));
        assertEquals(2, failureCallbackFailure.getSuppressed().length);
        assertSame(completionCallbackFailure, failureCallbackFailure.getSuppressed()[0]);
        Throwable reporterStartFailure = failureCallbackFailure.getSuppressed()[1];
        assertTrue(reporterStartFailure instanceof IllegalThreadStateException);
        assertEquals(Thread.class.getName(), reporterStartFailure.getStackTrace()[0].getClassName());
        assertEquals("start", reporterStartFailure.getStackTrace()[0].getMethodName());
        assertEquals(1, stdout.closeCalls.get());
        assertTrue(eventually(() -> dispatcher.outstandingCount() == 0 && publisher.ownerCount() == 0));
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

    private static BiConsumer<BoundedFailureReporter.FailureTarget, Throwable> reporterWhoseOwnerCannotStart()
            throws InterruptedException {
        Thread exhaustedOwner =
                Threading.unstartedPlatformNonInheriting("procwright-test-exhausted-reporter-owner-", () -> {});
        exhaustedOwner.start();
        exhaustedOwner.join(1_000);
        assertFalse(exhaustedOwner.isAlive());
        return (failureTarget, failure) -> exhaustedOwner.start();
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

    @SuppressWarnings("serial")
    private static final class BlockingCauseError extends AssertionError {

        private final CountDownLatch causeAccessed = new CountDownLatch(1);
        private final CountDownLatch releaseCause = new CountDownLatch(1);

        private BlockingCauseError() {
            super("stdout failure callback failed", null);
        }

        @Override
        public synchronized Throwable getCause() {
            causeAccessed.countDown();
            BlockingReadInputStream.awaitUninterruptibly(releaseCause);
            return null;
        }
    }
}
