/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

@Timeout(10)
final class ProcessScanOperationOwnerTest {

    @Test
    void completedScanReturnsItsValueAndReleasesCapacityBeforeReturning() {
        ProcessScanOperationOwner owner = ProcessScanOperationOwner.production(1);

        ProcessScanOperationOwner.Result<String> result =
                owner.scan("procwright-scan-value-", Duration.ofSeconds(1), () -> "scanned");

        assertTrue(result.completed());
        assertEquals("scanned", result.value());
        assertEquals(1, owner.availablePermits());
    }

    @Test
    void checkedAndRuntimeScanFailuresAreUnavailableAndReleaseCapacity() {
        ProcessScanOperationOwner owner = ProcessScanOperationOwner.production(1);
        for (Exception failure : List.of(new IOException("scan failed"), new SecurityException("scan denied"))) {
            ProcessScanOperationOwner.Result<Object> result =
                    owner.scan("procwright-unavailable-scan-", Duration.ofSeconds(1), () -> {
                        throw failure;
                    });

            assertFalse(result.completed());
            assertSame(ProcessScanOperationOwner.Result.Failure.UNAVAILABLE, result.failure());
            assertEquals(1, owner.availablePermits());
        }
    }

    @Test
    void timelyFatalScanFailureKeepsItsIdentityAndReleasesCapacity() {
        ProcessScanOperationOwner owner = ProcessScanOperationOwner.production(1);
        AssertionError expected = new AssertionError("fatal scan failure");

        AssertionError actual = assertThrows(
                AssertionError.class,
                () -> owner.scan("procwright-fatal-scan-", Duration.ofSeconds(1), () -> {
                    throw expected;
                }));

        assertSame(expected, actual);
        assertEquals(1, owner.availablePermits());
    }

    @Test
    void timeoutKeepsCapacityUntilTheScanPhysicallyFinishesAndIgnoresItsLateFailure() throws Exception {
        AtomicReference<Thread> worker = new AtomicReference<>();
        AtomicReference<Throwable> uncaught = new AtomicReference<>();
        ProcessScanOperationOwner owner = new ProcessScanOperationOwner(1, (name, task) -> {
            Thread thread = new Thread(null, task, name, 0, false);
            thread.setUncaughtExceptionHandler((ignored, failure) -> uncaught.set(failure));
            worker.set(thread);
            return thread;
        });
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger rejectedCalls = new AtomicInteger();
        ProcessScanOperationOwner.Result<Object> result;
        try {
            result = owner.scan("procwright-late-scan-", Duration.ofMillis(25), () -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException interruption) {
                    interrupted.countDown();
                    awaitUninterruptibly(release);
                }
                throw new AssertionError("late scan failure");
            });

            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
            assertSame(ProcessScanOperationOwner.Result.Failure.DEADLINE, result.failure());
            assertFalse(result.completed());
            assertEquals(0, owner.availablePermits());
            ProcessScanOperationOwner.Result<Integer> rejected =
                    owner.scan("procwright-saturated-scan-", Duration.ofSeconds(1), rejectedCalls::incrementAndGet);
            assertSame(ProcessScanOperationOwner.Result.Failure.UNAVAILABLE, rejected.failure());
            assertEquals(0, rejectedCalls.get());
        } finally {
            release.countDown();
            worker.get().join(TimeUnit.SECONDS.toMillis(1));
        }

        assertFalse(worker.get().isAlive());
        assertNull(uncaught.get());
        assertEquals(1, owner.availablePermits());
        assertSame(ProcessScanOperationOwner.Result.Failure.DEADLINE, result.failure());
        assertTrue(owner.scan("procwright-recovered-scan-", Duration.ofSeconds(1), () -> null)
                .completed());
    }

    @Test
    void callerInterruptionRestoresItsFlagAndRetainsCapacityUntilScanCompletion() throws Exception {
        ProcessScanOperationOwner owner = ProcessScanOperationOwner.production(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch interrupted = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<ProcessScanOperationOwner.Result<Object>> result = new AtomicReference<>();
        AtomicReference<Boolean> callerInterrupted = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            result.set(owner.scan("procwright-interrupted-scan-", Duration.ofSeconds(5), () -> {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException interruption) {
                    interrupted.countDown();
                    awaitUninterruptibly(release);
                    throw interruption;
                }
                return null;
            }));
            callerInterrupted.set(Thread.currentThread().isInterrupted());
        });
        caller.start();
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(1));

            assertFalse(caller.isAlive());
            assertSame(
                    ProcessScanOperationOwner.Result.Failure.INTERRUPTED,
                    result.get().failure());
            assertTrue(callerInterrupted.get());
            assertTrue(interrupted.await(1, TimeUnit.SECONDS));
            assertEquals(0, owner.availablePermits());
        } finally {
            release.countDown();
            caller.join(TimeUnit.SECONDS.toMillis(1));
        }
        assertTrue(eventually(() -> owner.availablePermits() == 1));
    }

    @Test
    void callerInterruptionBeforeCallbackEntryReachesTheStartedWorker() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        AtomicReference<Boolean> callbackInterrupted = new AtomicReference<>();
        ProcessScanOperationOwner owner = new ProcessScanOperationOwner(
                1,
                (name, task) -> new Thread(
                        () -> {
                            workerStarted.countDown();
                            awaitUninterruptibly(releaseWorker);
                            task.run();
                        },
                        name));
        AtomicReference<ProcessScanOperationOwner.Result<Object>> result = new AtomicReference<>();
        Thread caller =
                new Thread(() -> result.set(owner.scan("procwright-before-scan-entry-", Duration.ofSeconds(5), () -> {
                    callbackInterrupted.set(Thread.currentThread().isInterrupted());
                    callbackEntered.countDown();
                    return null;
                })));
        caller.start();
        try {
            assertTrue(workerStarted.await(1, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(1));

            assertFalse(caller.isAlive());
            assertSame(
                    ProcessScanOperationOwner.Result.Failure.INTERRUPTED,
                    result.get().failure());
            assertEquals(0, owner.availablePermits());
        } finally {
            releaseWorker.countDown();
            caller.join(TimeUnit.SECONDS.toMillis(1));
        }

        assertTrue(callbackEntered.await(1, TimeUnit.SECONDS));
        assertTrue(callbackInterrupted.get());
        assertTrue(eventually(() -> owner.availablePermits() == 1));
    }

    @Test
    void fullCapacityRejectsAdditionalScansWithoutStartingOrQueueingThem() throws Exception {
        int capacity = 2;
        ProcessScanOperationOwner owner = ProcessScanOperationOwner.production(capacity);
        CountDownLatch entered = new CountDownLatch(capacity);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger rejectedCalls = new AtomicInteger();
        ExecutorService callers = Executors.newFixedThreadPool(capacity);
        List<Future<ProcessScanOperationOwner.Result<Object>>> scans = new ArrayList<>();
        try {
            for (int index = 0; index < capacity; index++) {
                scans.add(callers.submit(() -> owner.scan("procwright-capacity-scan-", Duration.ofSeconds(5), () -> {
                    entered.countDown();
                    awaitUninterruptibly(release);
                    return null;
                })));
            }
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertEquals(0, owner.availablePermits());

            ProcessScanOperationOwner.Result<Integer> rejected =
                    owner.scan("procwright-rejected-scan-", Duration.ofSeconds(1), rejectedCalls::incrementAndGet);

            assertSame(ProcessScanOperationOwner.Result.Failure.UNAVAILABLE, rejected.failure());
            assertEquals(0, rejectedCalls.get());
            release.countDown();
            for (Future<ProcessScanOperationOwner.Result<Object>> scan : scans) {
                assertTrue(scan.get(1, TimeUnit.SECONDS).completed());
            }
            assertEquals(capacity, owner.availablePermits());
        } finally {
            release.countDown();
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(1, TimeUnit.SECONDS));
        }
        assertEquals(0, rejectedCalls.get());
    }

    @Test
    void productionScanWorkerIsDaemonAndDoesNotInheritCallerThreadLocals() {
        ProcessScanOperationOwner owner = ProcessScanOperationOwner.production(1);
        InheritableThreadLocal<String> inherited = new InheritableThreadLocal<>();
        inherited.set("caller-state");
        try {
            assertTrue(owner.scan("procwright-production-scan-", Duration.ofSeconds(1), () -> {
                        assertNull(inherited.get());
                        assertTrue(Thread.currentThread().isDaemon());
                        return null;
                    })
                    .completed());
        } finally {
            inherited.remove();
        }
        assertEquals(1, owner.availablePermits());
    }

    @ParameterizedTest
    @CsvSource({"false, false", "false, true", "true, false", "true, true"})
    void workerCreationOrStartFailureRollsBackCapacityAndKeepsFatalIdentity(boolean failOnStart, boolean fatal) {
        Throwable expected = fatal ? new AssertionError("scan worker failed") : new SecurityException("scan denied");
        AtomicInteger attempts = new AtomicInteger();
        AtomicInteger scanCalls = new AtomicInteger();
        ProcessScanOperationOwner owner = new ProcessScanOperationOwner(1, (name, task) -> {
            if (!failOnStart && attempts.getAndIncrement() == 0) {
                throwUnchecked(expected);
            }
            return new Thread(null, task, name, 0, false) {
                @Override
                public synchronized void start() {
                    if (failOnStart && attempts.getAndIncrement() == 0) {
                        throwUnchecked(expected);
                    }
                    super.start();
                }
            };
        });

        if (fatal) {
            assertSame(
                    expected,
                    assertThrows(
                            Error.class,
                            () -> owner.scan(
                                    "procwright-worker-failure-", Duration.ofSeconds(1), scanCalls::incrementAndGet)));
        } else {
            assertSame(
                    ProcessScanOperationOwner.Result.Failure.UNAVAILABLE,
                    owner.scan("procwright-worker-failure-", Duration.ofSeconds(1), scanCalls::incrementAndGet)
                            .failure());
        }
        assertEquals(0, scanCalls.get());
        assertEquals(1, owner.availablePermits());

        ProcessScanOperationOwner.Result<Integer> recovered =
                owner.scan("procwright-worker-recovered-", Duration.ofSeconds(1), scanCalls::incrementAndGet);
        assertTrue(recovered.completed());
        assertEquals(1, recovered.value());
        assertEquals(1, scanCalls.get());
        assertEquals(1, owner.availablePermits());
    }

    private static void throwUnchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        throw (Error) failure;
    }

    private static boolean eventually(java.util.function.BooleanSupplier condition) {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (!condition.getAsBoolean() && deadline - System.nanoTime() > 0) {
            Thread.onSpinWait();
        }
        return condition.getAsBoolean();
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean restoreInterrupt = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException interruption) {
                restoreInterrupt = true;
            }
        }
        if (restoreInterrupt) {
            Thread.currentThread().interrupt();
        }
    }
}
