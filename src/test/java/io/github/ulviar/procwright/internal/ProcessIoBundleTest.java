/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ProcessIoBundleTest extends ProcessIoResourcesTestSupport {

    @Test
    void outputPairUsesTheRemainingSlotAndSettlesRejectedOutputSeparately() throws Exception {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 1);
        CountDownLatch occupyingCloseStarted = new CountDownLatch(1);
        CountDownLatch releaseOccupyingClose = new CountDownLatch(1);
        dispatcher.dispatch(BoundedCloseDispatcher.ownedCloseRequest(
                () -> {
                    occupyingCloseStarted.countDown();
                    try {
                        releaseOccupyingClose.await();
                    } catch (InterruptedException interruption) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(interruption);
                    }
                },
                "occupying-close-",
                ignored -> {}));
        TrackingProcess process = new TrackingProcess();
        ProcessIoResources resources = ProcessIoResources.acquire(process, dispatcher);
        AtomicReference<Throwable> stderrFailure = new AtomicReference<>();
        CountDownLatch failureReported = new CountDownLatch(1);
        try {
            assertTrue(occupyingCloseStarted.await(1, TimeUnit.SECONDS));

            ProcessStreamResource.closePairAsync(
                    resources.stdout(),
                    "stdout-close-",
                    ignored -> {},
                    resources.stderr(),
                    "stderr-close-",
                    failure -> {
                        stderrFailure.set(failure);
                        failureReported.countDown();
                    });

            assertTrue(resources.stdout().closeStarted());
            assertTrue(resources.stderr().closeStarted());
            assertFalse(resources.stdout().closeOutcome().isDone());
            Throwable rejected =
                    resources.stderr().closeOutcome().get(1, TimeUnit.SECONDS).failure();
            assertTrue(rejected instanceof RejectedExecutionException);
            assertTrue(failureReported.await(1, TimeUnit.SECONDS));
            assertSame(rejected, stderrFailure.get());
            assertEquals(1, dispatcher.pendingCount());
            assertEquals(2, dispatcher.outstandingCount());

            releaseOccupyingClose.countDown();
            assertNull(
                    resources.stdout().closeOutcome().get(1, TimeUnit.SECONDS).failure());
            assertSame(rejected, resources.stderr().closeOutcome().join().failure());
            resources.stdout().closeInline();
            resources.stderr().closeInline();
            assertEquals(1, process.stdout.closeCalls.get());
            assertEquals(0, process.stderr.closeCalls.get());
            assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
        } finally {
            releaseOccupyingClose.countDown();
            resources.stdin().closeInline();
        }
    }

    @Test
    void invalidPairArgumentsDoNotClaimResourcesOrConsumeCloseCapacity() throws Exception {
        for (InvalidPairArgument invalidArgument : InvalidPairArgument.values()) {
            assertInvalidPairLeavesResourcesUsable(invalidArgument);
        }
    }

    @Test
    void singleAndPairCloseLinearizeWithoutPartialClaimOrDuplicatePhysicalClose() throws Exception {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(3, 3);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            for (int attempt = 0; attempt < 32; attempt++) {
                TrackingProcess process = new TrackingProcess();
                ProcessIoResources resources = ProcessIoResources.acquire(process, dispatcher);
                CountDownLatch start = new CountDownLatch(1);
                Future<Throwable> single = executor.submit(() -> {
                    start.await();
                    return captureFailure(
                            () -> resources.stdout().closeAsync("procwright-racing-single-close-", ignored -> {}));
                });
                Future<Throwable> pair = executor.submit(() -> {
                    start.await();
                    return captureFailure(() -> closeOutputPair(resources));
                });

                start.countDown();
                assertNull(single.get(1, TimeUnit.SECONDS));
                Throwable pairFailure = pair.get(1, TimeUnit.SECONDS);
                assertTrue(pairFailure == null || pairFailure instanceof IllegalStateException);

                resources.closeAllAsync(ignored -> {});
                assertNull(resources.awaitClose(Duration.ofSeconds(1)));
                assertEquals(1, process.stdin.closeCalls.get());
                assertEquals(1, process.stdout.closeCalls.get());
                assertEquals(1, process.stderr.closeCalls.get());
                assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
            }
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void closeAllSettlesEveryOwnedStreamWhenAnyStarterFails() throws Exception {
        for (int failedOrdinal = 1; failedOrdinal <= 3; failedOrdinal++) {
            int expectedFailedOrdinal = failedOrdinal;
            for (Throwable expected : java.util.List.of(
                    new IllegalStateException("starter " + failedOrdinal + " failed"),
                    new AssertionError("starter " + failedOrdinal + " failed"))) {
                AtomicInteger starts = new AtomicInteger();
                BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2, (name, task) -> {
                    if (starts.incrementAndGet() == expectedFailedOrdinal) {
                        throwFailure(expected);
                    }
                    Threading.start(name, task);
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
                assertEquals(failedOrdinal == 1 ? 0 : 1, process.stdin.closeCalls.get());
                assertEquals(failedOrdinal == 2 ? 0 : 1, process.stdout.closeCalls.get());
                assertEquals(failedOrdinal == 3 ? 0 : 1, process.stderr.closeCalls.get());
            }
        }
    }

    @Test
    void closeAllKeepsStarterFailuresInsidePhysicalCloseOutcomes() throws Exception {
        IllegalStateException stdinFailure = new IllegalStateException("stdin starter failed");
        IllegalStateException stdoutFailure = new IllegalStateException("stdout starter failed");
        IllegalStateException stderrFailure = new IllegalStateException("stderr starter failed");
        java.util.List<Throwable> failures = java.util.List.of(stdinFailure, stdoutFailure, stderrFailure);
        AtomicInteger starts = new AtomicInteger();
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(3, 3, (name, task) -> {
            throw (IllegalStateException) failures.get(starts.getAndIncrement());
        });
        TrackingProcess process = new TrackingProcess();
        ProcessIoResources resources = ProcessIoResources.acquire(process, dispatcher);

        Throwable synchronousFailure = captureFailure(() -> resources.closeAllAsync(ignored -> {}));

        assertNull(synchronousFailure);
        assertTrue(resources.awaitClose(Duration.ofSeconds(1)) != null);
        failures.forEach(failure -> assertEquals(0, failure.getSuppressed().length));
        assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
        assertEquals(0, process.stdin.closeCalls.get());
        assertEquals(0, process.stdout.closeCalls.get());
        assertEquals(0, process.stderr.closeCalls.get());
    }

    private static void assertInvalidPairLeavesResourcesUsable(InvalidPairArgument invalidArgument) throws Exception {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(3, 3);
        TrackingProcess process = new TrackingProcess();
        TrackingProcess foreignProcess = new TrackingProcess();
        ProcessIoResources resources = ProcessIoResources.acquire(process, dispatcher);
        ProcessIoResources foreign = ProcessIoResources.acquire(foreignProcess, dispatcher);
        try {
            assertThrows(
                    invalidArgument.expectedType(),
                    () -> invalidArgument.invoke(resources, foreign),
                    invalidArgument.toString());
            assertFalse(resources.stdout().closeStarted(), invalidArgument.toString());
            assertFalse(resources.stderr().closeStarted(), invalidArgument.toString());
            assertEquals(0, dispatcher.outstandingCount(), invalidArgument.toString());

            closeOutputPair(resources);
            resources.stdin().closeInline();
            foreign.closeAllAsync(ignored -> {});

            assertNull(resources.awaitClose(Duration.ofSeconds(1)), invalidArgument.toString());
            assertNull(foreign.awaitClose(Duration.ofSeconds(1)), invalidArgument.toString());
            assertEquals(1, process.stdin.closeCalls.get(), invalidArgument.toString());
            assertEquals(1, process.stdout.closeCalls.get(), invalidArgument.toString());
            assertEquals(1, process.stderr.closeCalls.get(), invalidArgument.toString());
            assertTrue(eventually(() -> dispatcher.outstandingCount() == 0), invalidArgument.toString());
        } finally {
            captureFailure(() -> resources.closeAllAsync(ignored -> {}));
            captureFailure(() -> foreign.closeAllAsync(ignored -> {}));
        }
    }

    private static void closeOutputPair(ProcessIoResources resources) {
        ProcessStreamResource.closePairAsync(
                resources.stdout(),
                "procwright-test-stdout-close-",
                ignored -> {},
                resources.stderr(),
                "procwright-test-stderr-close-",
                ignored -> {});
    }

    private enum InvalidPairArgument {
        NULL_FIRST_RESOURCE(NullPointerException.class),
        NULL_SECOND_RESOURCE(NullPointerException.class),
        SAME_RESOURCE(IllegalArgumentException.class),
        FOREIGN_RESOURCE(IllegalArgumentException.class),
        NULL_FIRST_PREFIX(NullPointerException.class),
        NULL_FIRST_FAILURE_HANDLER(NullPointerException.class),
        NULL_SECOND_PREFIX(NullPointerException.class),
        NULL_SECOND_FAILURE_HANDLER(NullPointerException.class);

        private final Class<? extends Throwable> expectedType;

        InvalidPairArgument(Class<? extends Throwable> expectedType) {
            this.expectedType = expectedType;
        }

        private Class<? extends Throwable> expectedType() {
            return expectedType;
        }

        private void invoke(ProcessIoResources resources, ProcessIoResources foreign) {
            ProcessStreamResource.closePairAsync(
                    this == NULL_FIRST_RESOURCE ? null : resources.stdout(),
                    this == NULL_FIRST_PREFIX ? null : "first-",
                    this == NULL_FIRST_FAILURE_HANDLER ? null : ignored -> {},
                    switch (this) {
                        case NULL_SECOND_RESOURCE -> null;
                        case SAME_RESOURCE -> resources.stdout();
                        case FOREIGN_RESOURCE -> foreign.stderr();
                        default -> resources.stderr();
                    },
                    this == NULL_SECOND_PREFIX ? null : "second-",
                    this == NULL_SECOND_FAILURE_HANDLER ? null : ignored -> {});
        }
    }
}
