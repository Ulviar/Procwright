/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static io.github.ulviar.procwright.internal.ThrowableMonitorTestSupport.hold;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
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
    void callbackFailureCannotDelayAnIndependentStreamClose() throws Exception {
        IOException physicalFailure = new IOException("stdout physical close failed");
        AssertionError callbackFailure = new AssertionError("stdout failure callback failed");
        TrackingInputStream stdout = failingInput(physicalFailure);
        TrackingProcess process = new TrackingProcess(stdout, new TrackingInputStream());
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 1, 3);
        AtomicReference<Throwable> reported = new AtomicReference<>();
        CountDownLatch callbackEntered = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        ProcessIoResources resources = ProcessIoResources.acquire(
                process,
                dispatcher,
                new BoundedLifecyclePublisher(3),
                ignored -> {},
                (target, failure) -> reported.set(failure));

        try {
            resources
                    .stdout()
                    .closeOwnedAsync(
                            "procwright-callback-failure-",
                            failure -> {
                                callbackEntered.countDown();
                                BlockingReadInputStream.awaitUninterruptibly(releaseCallback);
                                throw callbackFailure;
                            },
                            () -> {});
            assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));

            resources.stderr().closeAsync("procwright-independent-stderr-close-", ignored -> {});
            resources.stderr().closeCompletion().get(1, TimeUnit.SECONDS);
        } finally {
            releaseCallback.countDown();
        }

        assertTrue(eventually(() -> reported.get() == callbackFailure));
        resources.closeAllAsync(ignored -> {});
        assertSame(physicalFailure, resources.awaitClose(Duration.ofSeconds(1)));
        assertEquals(0, physicalFailure.getSuppressed().length);
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
                    .thenRun(() -> {
                        Throwable result = resources.stdout().closeResult();
                        completeFailureGraphObserved.set(result != null
                                && result.getCause() == startFailure
                                && java.util.List.of(closeFailure).equals(java.util.List.of(result.getSuppressed())));
                    });
            Future<Throwable> awaitClose = waiter.submit(() -> resources.awaitClose(Duration.ofSeconds(2)));

            assertFalse(resources.stdout().closeCompletion().isDone());
            assertFalse(awaitClose.isDone());

            stdout.releaseClose.countDown();
            Throwable result = awaitClose.get(1, TimeUnit.SECONDS);
            assertSame(result, resources.stdout().closeResult());
            assertSame(startFailure, result.getCause());
            assertEquals(java.util.List.of(closeFailure), java.util.List.of(result.getSuppressed()));
            assertEquals(0, startFailure.getSuppressed().length);
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
    void failureTargetCaptureCannotStrandOwnedCloseCallbacksOrCompletion() throws Exception {
        AssertionError captureFailure = new AssertionError("context loader unavailable");
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2, 3, (name, task) -> {
            Thread owner = new Thread(task, name) {
                @Override
                public ClassLoader getContextClassLoader() {
                    throw captureFailure;
                }
            };
            owner.start();
        });
        BoundedLifecyclePublisher publisher = new BoundedLifecyclePublisher(3);
        ProcessIoResources resources = ProcessIoResources.acquire(new TrackingProcess(), dispatcher, publisher);
        CountDownLatch callbackCompleted = new CountDownLatch(1);

        resources
                .stdout()
                .closeOwnedAsync("procwright-hostile-target-close-", ignored -> {}, callbackCompleted::countDown);

        assertTrue(callbackCompleted.await(1, TimeUnit.SECONDS));
        resources.stdout().closeCompletion().get(1, TimeUnit.SECONDS);
        assertNull(resources.stdout().closeResult());
        resources.closeAllAsync(ignored -> {});
        assertTrue(eventually(() -> dispatcher.outstandingCount() == 0 && publisher.ownerCount() == 0));
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
        Throwable reportedFailure = uncaught.get();
        assertInstanceOf(Error.class, reportedFailure);
        assertSame(failureCallbackFailure, FailureAggregation.primary(reportedFailure));
        assertSame(physicalFailure, resources.stdout().closeResult());
        assertEquals(java.util.List.of(), java.util.List.of(physicalFailure.getSuppressed()));
        assertEquals(0, failureCallbackFailure.getSuppressed().length);
        assertEquals(0, completionCallbackFailure.getSuppressed().length);
        java.util.List<Throwable> reportedSources = FailureAggregation.sources(reportedFailure);
        assertEquals(3, reportedSources.size());
        assertSame(failureCallbackFailure, reportedSources.get(0));
        assertSame(completionCallbackFailure, reportedSources.get(1));
        Throwable reporterStartFailure = reportedSources.get(2);
        assertTrue(reporterStartFailure instanceof IllegalThreadStateException);
        assertEquals(Thread.class.getName(), reporterStartFailure.getStackTrace()[0].getClassName());
        assertEquals("start", reporterStartFailure.getStackTrace()[0].getMethodName());
        assertEquals(1, stdout.closeCalls.get());
        assertTrue(eventually(() -> dispatcher.outstandingCount() == 0 && publisher.ownerCount() == 0));
    }

    @Test
    void callbackFailureAggregationDoesNotAcquireTheSourceMonitorBeforeSettlement() throws Exception {
        IOException physicalFailure = new IOException("stdout close failed");
        AssertionError failureCallbackFailure = new AssertionError("failure callback failed");
        IllegalStateException completionCallbackFailure = new IllegalStateException("completion callback failed");
        AtomicReference<Throwable> reported = new AtomicReference<>();
        TrackingProcess process = new TrackingProcess(failingInput(physicalFailure), new TrackingInputStream());
        ProcessIoResources resources = ProcessIoResources.acquire(
                process,
                new BoundedCloseDispatcher(1, 2, 3),
                new BoundedLifecyclePublisher(3),
                ignored -> {},
                (target, failure) -> reported.set(failure));

        try (var monitor = hold(failureCallbackFailure)) {
            monitor.verifyHeld();
            resources
                    .stdout()
                    .closeOwnedAsync(
                            "procwright-detached-close-failure-",
                            ignored -> {
                                throw failureCallbackFailure;
                            },
                            () -> {
                                throw completionCallbackFailure;
                            });

            resources.stdout().closeCompletion().get(1, TimeUnit.SECONDS);
            assertSame(physicalFailure, resources.stdout().closeResult());
            assertTrue(eventually(() -> reported.get() != null));
            assertEquals(
                    java.util.List.of(failureCallbackFailure, completionCallbackFailure),
                    FailureAggregation.sources(reported.get()));
        } finally {
            resources.closeAllAsync(ignored -> {});
        }
    }

    @Test
    void callbackFailureIsDiagnosticAndCannotMutateACompletedCloseResult() throws Exception {
        AssertionError callbackFailure = new AssertionError("completion callback failed");
        AtomicReference<Throwable> reported = new AtomicReference<>();
        TrackingProcess process = new TrackingProcess(new TrackingInputStream(), new TrackingInputStream());
        ProcessIoResources resources = ProcessIoResources.acquire(
                process,
                new BoundedCloseDispatcher(1, 2, 3),
                new BoundedLifecyclePublisher(3),
                ignored -> {},
                (target, failure) -> reported.set(failure));
        AtomicReference<Throwable> observedAtCompletion = new AtomicReference<>();
        CompletableFuture<Void> completion = resources.stdout().closeCompletion();
        completion.thenRun(() -> observedAtCompletion.set(resources.stdout().closeResult()));

        resources.stdout().closeOwnedAsync("procwright-callback-diagnostic-", ignored -> {}, () -> {
            throw callbackFailure;
        });

        completion.get(1, TimeUnit.SECONDS);
        assertTrue(eventually(() -> reported.get() == callbackFailure));
        assertNull(observedAtCompletion.get());
        assertNull(resources.stdout().closeResult());
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
            Throwable firstResult = resources.stdout().closeResult();
            Throwable secondResult = resources.stderr().closeResult();
            assertSame(firstResult, firstReported.get());
            assertSame(secondResult, secondReported.get());
            assertSame(firstStartFailure, firstResult.getCause());
            assertSame(secondStartFailure, secondResult.getCause());
            assertEquals(java.util.List.of(firstCloseFailure), java.util.List.of(firstResult.getSuppressed()));
            assertEquals(java.util.List.of(secondCloseFailure), java.util.List.of(secondResult.getSuppressed()));
            assertEquals(0, firstStartFailure.getSuppressed().length);
            assertEquals(0, secondStartFailure.getSuppressed().length);
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
}
