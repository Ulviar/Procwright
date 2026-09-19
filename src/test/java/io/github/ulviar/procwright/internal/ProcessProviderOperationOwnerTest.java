/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
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

final class ProcessProviderOperationOwnerTest {

    @Test
    void lateProviderFailureCannotChangeSelectedTimeoutOrRetainRecoveredCapacity() throws Exception {
        ProcessProviderOperationOwner owner = ProcessProviderOperationOwner.production(1);
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AssertionError lateFailure = new AssertionError("late provider failure");
        CommandExecutionException timeout;
        try {
            timeout = assertThrows(
                    CommandExecutionException.class,
                    () -> owner.required("procwright-late-provider-failure-", Duration.ofMillis(25), () -> {
                        entered.countDown();
                        awaitUninterruptibly(release);
                        throw lateFailure;
                    }));
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            assertTrue(ProcessProviderOperationOwner.causedByOperationDeadline(timeout));
            assertEquals(0, owner.availablePermits());
        } finally {
            release.countDown();
        }

        assertTrue(eventually(() -> owner.availablePermits() == 1));
        assertEquals(
                "recovered",
                owner.required("procwright-after-late-failure-", Duration.ofSeconds(1), () -> "recovered"));
        assertTrue(ProcessProviderOperationOwner.causedByOperationDeadline(timeout));
        assertEquals(0, timeout.getSuppressed().length);
    }

    @Test
    void callerInterruptionBeforeCallbackEntryReachesTheStartedWorker() throws Exception {
        CountDownLatch workerStarted = new CountDownLatch(1);
        CountDownLatch releaseWorker = new CountDownLatch(1);
        CountDownLatch callbackEntered = new CountDownLatch(1);
        AtomicReference<Boolean> callbackInterrupted = new AtomicReference<>();
        ProcessProviderOperationOwner owner = new ProcessProviderOperationOwner(
                1,
                (name, task) -> new Thread(
                        () -> {
                            workerStarted.countDown();
                            awaitUninterruptibly(releaseWorker);
                            task.run();
                        },
                        name));
        AtomicReference<Throwable> observed = new AtomicReference<>();
        Thread caller = new Thread(() -> observed.set(captureFailure(() ->
                owner.required("procwright-before-provider-entry-", Duration.ofSeconds(2), () -> {
                    callbackInterrupted.set(Thread.currentThread().isInterrupted());
                    callbackEntered.countDown();
                    return null;
                }))));
        caller.start();
        try {
            assertTrue(workerStarted.await(1, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(1));
            assertFalse(caller.isAlive());
            assertTrue(observed.get() instanceof InterruptedException);
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
    void bestEffortResultDistinguishesDeadlineFromUnavailableProviderState() throws Exception {
        ProcessProviderOperationOwner owner = ProcessProviderOperationOwner.production(1);
        CountDownLatch release = new CountDownLatch(1);

        ProcessProviderOperationOwner.BestEffortResult<String> deadline =
                owner.bestEffortResult("procwright-best-effort-deadline-", Duration.ofMillis(25), () -> {
                    awaitUninterruptibly(release);
                    return "late";
                });

        assertSame(ProcessProviderOperationOwner.BestEffortResult.Failure.DEADLINE, deadline.failure());
        assertFalse(deadline.completed());
        release.countDown();
        assertTrue(eventually(() -> owner.availablePermits() == 1));

        ProcessProviderOperationOwner.BestEffortResult<String> unavailable =
                owner.bestEffortResult("procwright-best-effort-unavailable-", Duration.ofSeconds(1), () -> {
                    throw new SecurityException("provider denied operation");
                });

        assertSame(ProcessProviderOperationOwner.BestEffortResult.Failure.UNAVAILABLE, unavailable.failure());
        assertFalse(unavailable.completed());
    }

    @Test
    void bestEffortResultDistinguishesCallerInterruption() throws Exception {
        ProcessProviderOperationOwner owner = ProcessProviderOperationOwner.production(1);
        CountDownLatch operationEntered = new CountDownLatch(1);
        CountDownLatch releaseOperation = new CountDownLatch(1);
        AtomicReference<ProcessProviderOperationOwner.BestEffortResult<String>> result = new AtomicReference<>();
        AtomicReference<Boolean> interrupted = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            result.set(owner.bestEffortResult("procwright-best-effort-interrupted-", Duration.ofSeconds(1), () -> {
                operationEntered.countDown();
                awaitUninterruptibly(releaseOperation);
                return "late";
            }));
            interrupted.set(Thread.currentThread().isInterrupted());
        });

        caller.start();
        try {
            assertTrue(operationEntered.await(1, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(1));

            assertFalse(caller.isAlive());
            assertSame(
                    ProcessProviderOperationOwner.BestEffortResult.Failure.INTERRUPTED,
                    result.get().failure());
            assertTrue(interrupted.get());
        } finally {
            releaseOperation.countDown();
            caller.join(TimeUnit.SECONDS.toMillis(1));
        }
        assertTrue(eventually(() -> owner.availablePermits() == 1));
    }

    @Test
    void ownerInterruptAfterProviderTimeoutDoesNotReportCheckedInterruption() throws Exception {
        assertOwnerInducedCheckedInterruptionIsSuppressed(false);
    }

    @Test
    void ownerInterruptAfterCallerInterruptionDoesNotReportCheckedInterruption() throws Exception {
        assertOwnerInducedCheckedInterruptionIsSuppressed(true);
    }

    private static void assertOwnerInducedCheckedInterruptionIsSuppressed(boolean interruptCaller) throws Exception {
        CountDownLatch operationEntered = new CountDownLatch(1);
        CountDownLatch operationInterrupted = new CountDownLatch(1);
        AtomicInteger reports = new AtomicInteger();
        ProcessProviderOperationOwner owner = new ProcessProviderOperationOwner(1, (threadPrefix, task) -> {
            Thread thread = new Thread(null, task, threadPrefix + "checked-interruption", 0, false);
            thread.setUncaughtExceptionHandler((ignored, failure) -> reports.incrementAndGet());
            return thread;
        });
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> result = caller.submit(() -> {
                try {
                    owner.required("procwright-scanner-interruption-", Duration.ofMillis(30), () -> {
                        operationEntered.countDown();
                        try {
                            new CountDownLatch(1).await();
                            return null;
                        } catch (InterruptedException interruption) {
                            operationInterrupted.countDown();
                            throw interruption;
                        }
                    });
                    return null;
                } catch (Throwable failure) {
                    return failure;
                }
            });
            assertTrue(operationEntered.await(1, TimeUnit.SECONDS));
            if (interruptCaller) {
                // The only caller owned by this executor is the required-operation invocation above.
                caller.shutdownNow();
            }

            Throwable failure = result.get(1, TimeUnit.SECONDS);
            if (interruptCaller) {
                assertTrue(failure instanceof InterruptedException, () -> String.valueOf(failure));
            } else {
                assertTrue(failure instanceof CommandExecutionException, () -> String.valueOf(failure));
            }
            assertTrue(operationInterrupted.await(1, TimeUnit.SECONDS));
            assertTrue(eventually(() -> owner.availablePermits() == 1));

            assertEquals(0, reports.get());
        } finally {
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void validationFailuresReleaseTheAcquiredPermitWithoutInvokingTheOperation() throws Exception {
        ProcessProviderOperationOwner owner =
                new ProcessProviderOperationOwner(1, Threading::unstartedPlatformNonInheriting);
        AtomicInteger operationCalls = new AtomicInteger();
        List<ThrowingRunnable> invalidInvocations = List.of(
                () -> owner.required(null, Duration.ofSeconds(1), () -> {
                    operationCalls.incrementAndGet();
                    return null;
                }),
                () -> owner.required("procwright-null-timeout-", null, () -> {
                    operationCalls.incrementAndGet();
                    return null;
                }),
                () -> owner.required("procwright-null-operation-", Duration.ofSeconds(1), null));

        for (ThrowingRunnable invalidInvocation : invalidInvocations) {
            assertThrows(NullPointerException.class, invalidInvocation::run);
            assertEquals(1, owner.availablePermits());
        }
        assertEquals(0, operationCalls.get());

        assertEquals(
                "recovered",
                owner.required("procwright-after-validation-failure-", Duration.ofSeconds(1), () -> "recovered"));
        assertEquals(1, owner.availablePermits());
    }

    @Test
    void sharedSizedOwnerRunsThirtyTwoOperationsAndRejectsTheThirtyThirdWithoutQueueing() throws Exception {
        int capacity = ProcessTreeScanner.SHARED_OPERATION_CAPACITY;
        ProcessProviderOperationOwner owner = ProcessProviderOperationOwner.production(capacity);
        CountDownLatch entered = new CountDownLatch(capacity);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger providerCalls = new AtomicInteger();
        ExecutorService callers = Executors.newFixedThreadPool(capacity);
        List<Future<Void>> operations = new ArrayList<>();
        try {
            for (int index = 0; index < capacity; index++) {
                operations.add(
                        callers.submit(() -> owner.required("procwright-capacity-owner-", Duration.ofSeconds(5), () -> {
                            providerCalls.incrementAndGet();
                            entered.countDown();
                            awaitUninterruptibly(release);
                            return null;
                        })));
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(0, owner.availablePermits());

            AtomicInteger rejectedCalls = new AtomicInteger();
            ProcessProviderOperationOwner.BestEffortResult<Integer> rejected = owner.bestEffortResult(
                    "procwright-capacity-best-effort-", Duration.ofSeconds(1), rejectedCalls::incrementAndGet);
            assertFalse(rejected.completed());
            assertSame(ProcessProviderOperationOwner.BestEffortResult.Failure.UNAVAILABLE, rejected.failure());
            assertEquals(0, rejectedCalls.get());
            CommandExecutionException requiredFailure = assertThrows(
                    CommandExecutionException.class,
                    () -> owner.required("procwright-capacity-test-", Duration.ofSeconds(1), () -> "unreachable"));
            assertEquals(CommandExecutionException.Reason.RUNTIME_FAILURE, requiredFailure.reason());
            assertFalse(ProcessProviderOperationOwner.causedByOperationDeadline(requiredFailure));
            assertEquals(capacity, providerCalls.get());
        } finally {
            release.countDown();
            for (Future<Void> operation : operations) {
                operation.get(2, TimeUnit.SECONDS);
            }
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(1, TimeUnit.SECONDS));
        }
        assertTrue(eventually(() -> owner.availablePermits() == capacity));
    }

    @Test
    void productionProviderOwnerIsDaemonAndDoesNotInheritCallerThreadLocals() throws Exception {
        ProcessProviderOperationOwner owner = ProcessProviderOperationOwner.production(1);
        InheritableThreadLocal<String> inherited = new InheritableThreadLocal<>();
        inherited.set("caller-state");
        try {
            owner.required("procwright-production-scanner-owner-", Duration.ofSeconds(1), () -> {
                assertNull(inherited.get());
                assertTrue(Thread.currentThread().isDaemon());
                return null;
            });
        } finally {
            inherited.remove();
        }
        assertEquals(1, owner.availablePermits());
    }

    @Test
    void ownerRecoversAfterThreadFactoryRejectionWithoutInvokingRejectedOperation() {
        AtomicInteger factoryCalls = new AtomicInteger();
        AtomicInteger operationCalls = new AtomicInteger();
        ProcessProviderOperationOwner owner = new ProcessProviderOperationOwner(1, (threadPrefix, task) -> {
            if (factoryCalls.getAndIncrement() == 0) {
                throw new SecurityException("scan owner denied");
            }
            return new Thread(task, threadPrefix + factoryCalls.get());
        });

        ProcessProviderOperationOwner.BestEffortResult<Integer> rejected = owner.bestEffortResult(
                "procwright-rejected-owner-", Duration.ofMillis(50), operationCalls::incrementAndGet);
        assertFalse(rejected.completed());
        assertSame(ProcessProviderOperationOwner.BestEffortResult.Failure.UNAVAILABLE, rejected.failure());
        assertEquals(0, operationCalls.get());
        assertEquals(1, owner.availablePermits());

        ProcessProviderOperationOwner.BestEffortResult<Integer> recovered = owner.bestEffortResult(
                "procwright-recovered-owner-", Duration.ofMillis(50), operationCalls::incrementAndGet);
        assertTrue(recovered.completed());
        assertEquals(1, recovered.value());
        assertEquals(1, operationCalls.get());
        assertEquals(1, owner.availablePermits());
    }

    @Test
    void workerCreationFailuresRollbackOwnedResourcesAndLeaveTheOwnerUsable() throws Exception {
        for (SetupFailureKind failureKind : SetupFailureKind.values()) {
            assertWorkerCreationFailureRollsBack(failureKind);
        }
    }

    @Test
    void workerStartFailurePreservesIdentityAndRollsBackAdmission() throws Exception {
        SecurityException expected = new SecurityException("worker start denied");
        AtomicInteger starts = new AtomicInteger();
        AtomicInteger operationCalls = new AtomicInteger();
        ProcessProviderOperationOwner owner = new ProcessProviderOperationOwner(
                1, (threadName, task) -> new Thread(null, task, threadName, 0, false) {
                    @Override
                    public synchronized void start() {
                        if (starts.getAndIncrement() == 0) {
                            throw expected;
                        }
                        super.start();
                    }
                });

        SecurityException actual = assertThrows(
                SecurityException.class,
                () -> owner.required("procwright-worker-start-denied-", Duration.ofSeconds(1), () -> {
                    operationCalls.incrementAndGet();
                    return null;
                }));

        assertSame(expected, actual);
        assertEquals(0, operationCalls.get());
        assertEquals(1, owner.availablePermits());

        owner.required("procwright-after-worker-start-denial-", Duration.ofSeconds(1), () -> {
            operationCalls.incrementAndGet();
            return null;
        });
        assertEquals(1, operationCalls.get());
        assertEquals(1, owner.availablePermits());
    }

    private static void assertWorkerCreationFailureRollsBack(SetupFailureKind failureKind) throws Exception {
        Throwable expected = failureKind.failure();
        AtomicInteger factoryCalls = new AtomicInteger();
        AtomicInteger operationCalls = new AtomicInteger();
        ProcessProviderOperationOwner owner = new ProcessProviderOperationOwner(1, (threadPrefix, task) -> {
            if (factoryCalls.getAndIncrement() == 0) {
                throwUnchecked(expected);
            }
            return new Thread(task, threadPrefix + factoryCalls.get());
        });

        Throwable actual =
                captureFailure(() -> owner.required("procwright-worker-start-failure-", Duration.ofSeconds(1), () -> {
                    operationCalls.incrementAndGet();
                    return null;
                }));

        assertSame(expected, actual);
        assertEquals(0, operationCalls.get());
        assertEquals(1, owner.availablePermits());

        owner.required("procwright-after-worker-start-failure-", Duration.ofSeconds(1), () -> {
            operationCalls.incrementAndGet();
            return null;
        });
        assertEquals(1, operationCalls.get());
        assertEquals(1, owner.availablePermits());
    }

    private static Throwable captureFailure(ThrowingRunnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
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

    @FunctionalInterface
    private interface ThrowingRunnable {

        void run() throws Exception;
    }

    private enum SetupFailureKind {
        RUNTIME_EXCEPTION {
            @Override
            Throwable failure() {
                return new IllegalStateException("worker creation failed");
            }
        },
        ERROR {
            @Override
            Throwable failure() {
                return new AssertionError("worker creation failed");
            }
        },
        OUT_OF_MEMORY_ERROR {
            @Override
            Throwable failure() {
                return new OutOfMemoryError("worker creation failed");
            }
        };

        abstract Throwable failure();
    }
}
