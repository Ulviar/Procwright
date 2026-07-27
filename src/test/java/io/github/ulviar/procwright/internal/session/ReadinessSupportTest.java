/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.ThrowableMonitorTestSupport.hold;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.internal.BoundedFailureReporterTestSupport;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ReadinessSupportTest {

    @Test
    void readinessSuccessReturnsWithoutClosingTarget() {
        AtomicInteger probes = new AtomicInteger();
        AtomicInteger closes = new AtomicInteger();

        ReadinessSupport.check(
                "target", ignored -> probes.incrementAndGet(), Duration.ofSeconds(1), closes::incrementAndGet);

        assertEquals(1, probes.get());
        assertEquals(0, closes.get());
    }

    @Test
    void readinessFailureUsesAFreshTypedEnvelopeForCloseContext() {
        IllegalStateException readinessFailure = new IllegalStateException("not ready");
        IllegalArgumentException closeFailure = new IllegalArgumentException("could not close");

        CommandExecutionException exception = assertThrows(
                CommandExecutionException.class,
                () -> ReadinessSupport.check(
                        "target",
                        ignored -> {
                            throw readinessFailure;
                        },
                        Duration.ofSeconds(1),
                        () -> {
                            throw closeFailure;
                        }));

        assertEquals(CommandExecutionException.Reason.READINESS_FAILED, exception.reason());
        assertSame(readinessFailure, exception.getCause());
        assertEquals(1, exception.getSuppressed().length);
        assertSame(closeFailure, exception.getSuppressed()[0]);
        assertEquals(0, readinessFailure.getSuppressed().length);
        assertEquals(0, closeFailure.getSuppressed().length);
    }

    @Test
    void fatalReadinessFailurePreservesIdentityAndReportsCloseFailureExactlyOnce() throws Exception {
        AssertionError readinessFailure = new AssertionError("fatal readiness failure");
        IllegalStateException closeFailure = new IllegalStateException("close failed");
        AtomicInteger closes = new AtomicInteger();
        AtomicInteger matchingReports = new AtomicInteger();
        CountDownLatch reported = new CountDownLatch(1);
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            if (failure == closeFailure) {
                matchingReports.incrementAndGet();
                reported.countDown();
            }
        });

        try {
            AssertionError thrown = assertThrows(
                    AssertionError.class,
                    () -> ReadinessSupport.check(
                            "target",
                            ignored -> {
                                throw readinessFailure;
                            },
                            Duration.ofSeconds(1),
                            () -> {
                                closes.incrementAndGet();
                                throw closeFailure;
                            }));

            assertSame(readinessFailure, thrown);
            assertEquals(1, closes.get());
            assertTrue(reported.await(1, TimeUnit.SECONDS));
            assertTrue(BoundedFailureReporterTestSupport.awaitSharedSettlement(Duration.ofSeconds(1)));
            assertEquals(1, matchingReports.get());
            assertEquals(0, thrown.getSuppressed().length);
            assertEquals(0, closeFailure.getSuppressed().length);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Test
    void fatalReadinessCleanupDoesNotWaitForTheForeignFailureMonitor() throws Exception {
        AssertionError readinessFailure = new AssertionError("fatal readiness failure");
        AtomicInteger closes = new AtomicInteger();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (var monitor = hold(readinessFailure)) {
            monitor.verifyHeld();
            Future<Throwable> outcome = executor.submit(() -> captureFailure(() -> ReadinessSupport.check(
                    "target",
                    ignored -> {
                        throw readinessFailure;
                    },
                    Duration.ofSeconds(1),
                    () -> {
                        closes.incrementAndGet();
                        throw new IllegalStateException("close failed");
                    })));

            assertSame(readinessFailure, outcome.get(1, TimeUnit.SECONDS));
            assertEquals(1, closes.get());
        } finally {
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
        assertEquals(0, readinessFailure.getSuppressed().length);
    }

    @Test
    void timeoutReturnsWhileNonCooperativeProbeRunsAndClosesExactlyOnce() throws Exception {
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        CountDownLatch probeExited = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> outcome = executor.submit(() -> captureFailure(() -> ReadinessSupport.check(
                    "target",
                    ignored -> {
                        probeStarted.countDown();
                        try {
                            awaitIgnoringInterrupts(releaseProbe);
                        } finally {
                            probeExited.countDown();
                        }
                    },
                    Duration.ofMillis(50),
                    closes::incrementAndGet)));

            assertTrue(probeStarted.await(1, TimeUnit.SECONDS));
            CommandExecutionException failure = (CommandExecutionException) outcome.get(1, TimeUnit.SECONDS);

            assertEquals(CommandExecutionException.Reason.READINESS_TIMEOUT, failure.reason());
            assertEquals(1, closes.get());
            assertEquals(1, probeExited.getCount(), "timed-out probe may still be running");
        } finally {
            releaseProbe.countDown();
            assertTrue(probeExited.await(1, TimeUnit.SECONDS));
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void callerInterruptionRestoresStatusAndClosesWhileProbeRetainsItsTask() throws Exception {
        CountDownLatch probeStarted = new CountDownLatch(1);
        CountDownLatch releaseProbe = new CountDownLatch(1);
        CountDownLatch probeExited = new CountDownLatch(1);
        CountDownLatch callerExited = new CountDownLatch(1);
        AtomicInteger closes = new AtomicInteger();
        AtomicReference<Throwable> outcome = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        Thread caller = new Thread(
                () -> {
                    try {
                        ReadinessSupport.check(
                                "target",
                                ignored -> {
                                    probeStarted.countDown();
                                    try {
                                        awaitIgnoringInterrupts(releaseProbe);
                                    } finally {
                                        probeExited.countDown();
                                    }
                                },
                                Duration.ofSeconds(5),
                                closes::incrementAndGet);
                    } catch (Throwable failure) {
                        outcome.set(failure);
                        interruptRestored.set(Thread.currentThread().isInterrupted());
                    } finally {
                        callerExited.countDown();
                    }
                },
                "readiness-interruption-test");
        try {
            caller.start();
            assertTrue(probeStarted.await(1, TimeUnit.SECONDS));

            caller.interrupt();

            assertTrue(callerExited.await(1, TimeUnit.SECONDS));
            CommandExecutionException failure = (CommandExecutionException) outcome.get();
            assertEquals(CommandExecutionException.Reason.READINESS_FAILED, failure.reason());
            assertTrue(failure.getCause() instanceof InterruptedException);
            assertTrue(interruptRestored.get());
            assertEquals(1, closes.get());
            assertEquals(1, probeExited.getCount(), "interrupted probe may still be running");
        } finally {
            releaseProbe.countDown();
            assertTrue(probeExited.await(1, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(1));
            assertFalse(caller.isAlive());
        }
    }

    private static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            throw new AssertionError("expected operation to fail");
        } catch (Throwable failure) {
            return failure;
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
