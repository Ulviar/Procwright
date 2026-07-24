/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
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
    void abandonedCompletedCarrierReportsItsEmbeddedFailure() throws Exception {
        CountDownLatch operationEntered = new CountDownLatch(1);
        CountDownLatch releaseOperation = new CountDownLatch(1);
        CountDownLatch failureReported = new CountDownLatch(1);
        AtomicReference<Throwable> reported = new AtomicReference<>();
        AssertionError embedded = new AssertionError("embedded provider failure");
        BoundedFailureReporter failureReporter = new BoundedFailureReporter(1, 4);
        ProcessProviderOperationOwner owner = new ProcessProviderOperationOwner(
                1,
                (threadPrefix, task) -> {
                    Thread thread = new Thread(task, threadPrefix + "carrier");
                    thread.setUncaughtExceptionHandler((ignored, failure) -> {
                        reported.set(failure);
                        failureReported.countDown();
                    });
                    return thread;
                },
                failureReporter);

        ProcessProviderOperationOwner.BestEffortResult<EmbeddedFailureValue> result;
        try {
            result = owner.bestEffortResult("procwright-embedded-failure-", Duration.ofMillis(25), () -> {
                operationEntered.countDown();
                awaitUninterruptibly(releaseOperation);
                return new EmbeddedFailureValue(embedded);
            });
            assertTrue(operationEntered.await(1, TimeUnit.SECONDS));
            assertSame(ProcessProviderOperationOwner.BestEffortResult.Failure.DEADLINE, result.failure());
        } finally {
            releaseOperation.countDown();
        }
        assertTrue(failureReported.await(1, TimeUnit.SECONDS));
        assertSame(embedded, reported.get());
        assertTrue(failureReporter.awaitSettlement(Duration.ofSeconds(1)));
        assertEquals(1, owner.availablePermits());
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
        BoundedFailureReporter failureReporter = new BoundedFailureReporter(1, 4);
        ProcessProviderOperationOwner owner = new ProcessProviderOperationOwner(
                1,
                (threadPrefix, task) -> {
                    Thread thread = new Thread(null, task, threadPrefix + "checked-interruption", 0, false);
                    thread.setUncaughtExceptionHandler((ignored, failure) -> reports.incrementAndGet());
                    return thread;
                },
                failureReporter);
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
            assertTrue(failureReporter.awaitSettlement(Duration.ofSeconds(1)));
            assertEquals(0, reports.get());
        } finally {
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void abandonedProviderErrorIsReportedExactlyOnceAfterPermitRecovery() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch reported = new CountDownLatch(1);
        AssertionError lateError = new AssertionError("late provider error");
        AtomicInteger reports = new AtomicInteger();
        BoundedFailureReporter failureReporter = new BoundedFailureReporter(1, 4);
        ProcessProviderOperationOwner owner = new ProcessProviderOperationOwner(
                1,
                (threadPrefix, task) -> {
                    Thread thread = new Thread(null, task, threadPrefix + "late-error", 0, false);
                    thread.setUncaughtExceptionHandler((ignored, failure) -> {
                        if (failure == lateError) {
                            reports.incrementAndGet();
                            reported.countDown();
                        }
                    });
                    return thread;
                },
                failureReporter);

        assertThrows(
                CommandExecutionException.class,
                () -> owner.required("procwright-scanner-error-", Duration.ofMillis(25), () -> {
                    entered.countDown();
                    awaitUninterruptibly(release);
                    throw lateError;
                }));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        release.countDown();

        assertTrue(reported.await(1, TimeUnit.SECONDS));
        assertTrue(eventually(() -> owner.availablePermits() == 1));
        assertTrue(failureReporter.awaitSettlement(Duration.ofSeconds(1)));
        assertEquals(1, reports.get());
    }

    @Test
    void validationFailuresReleaseTheAcquiredPermitWithoutCreatingAProducer() throws Exception {
        BoundedFailureReporter failureReporter = new BoundedFailureReporter(1, 4);
        ProcessProviderOperationOwner owner =
                new ProcessProviderOperationOwner(1, Threading::unstartedPlatformNonInheriting, failureReporter);
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
            assertTrue(failureReporter.awaitSettlement(Duration.ofMillis(100)));
        }
        assertEquals(0, operationCalls.get());

        assertEquals(
                "recovered",
                owner.required("procwright-after-validation-failure-", Duration.ofSeconds(1), () -> "recovered"));
        assertEquals(1, owner.availablePermits());
        assertTrue(failureReporter.awaitSettlement(Duration.ofSeconds(1)));
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
            long started = System.nanoTime();
            ProcessProviderOperationOwner.BestEffortResult<Integer> rejected = owner.bestEffortResult(
                    "procwright-capacity-best-effort-", Duration.ofSeconds(1), rejectedCalls::incrementAndGet);
            assertFalse(rejected.completed());
            assertSame(ProcessProviderOperationOwner.BestEffortResult.Failure.UNAVAILABLE, rejected.failure());
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            assertEquals(0, rejectedCalls.get());
            assertTrue(elapsed.compareTo(Duration.ofMillis(100)) < 0, () -> "capacity rejection took " + elapsed);
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
    void disposableProviderWorkersDoNotCarryThreadLocalOrStandardThreadState() throws Exception {
        ClassLoader baselineLoader = new ClassLoader(null) {};
        ClassLoader contaminatedLoader = new ClassLoader(null) {};
        Thread.UncaughtExceptionHandler baselineHandler = (thread, failure) -> {};
        Thread.UncaughtExceptionHandler contaminatedHandler = (thread, failure) -> {};
        AtomicInteger threadsCreated = new AtomicInteger();
        AtomicReference<Thread> firstOwner = new AtomicReference<>();
        ThreadLocal<Object> contamination = new ThreadLocal<>();
        Object retainedGraph = new Object();
        InheritableThreadLocal<String> inherited = new InheritableThreadLocal<>();
        inherited.set("caller-state");
        ProcessProviderOperationOwner owner = new ProcessProviderOperationOwner(
                1,
                (threadName, task) -> {
                    Thread thread =
                            new Thread(null, task, threadName + "-" + threadsCreated.incrementAndGet(), 0, false);
                    thread.setContextClassLoader(baselineLoader);
                    thread.setUncaughtExceptionHandler(baselineHandler);
                    thread.setPriority(Thread.NORM_PRIORITY);
                    return thread;
                },
                new BoundedFailureReporter(1, 4));
        try {
            owner.required("procwright-scanner-dirty-", Duration.ofSeconds(1), () -> {
                Thread worker = Thread.currentThread();
                assertNull(inherited.get(), "scanner owner inherited caller ThreadLocal state");
                firstOwner.set(worker);
                contamination.set(retainedGraph);
                worker.setContextClassLoader(contaminatedLoader);
                worker.setUncaughtExceptionHandler(contaminatedHandler);
                worker.setPriority(Thread.MIN_PRIORITY);
                worker.interrupt();
                return null;
            });

            owner.required("procwright-scanner-clean-", Duration.ofSeconds(1), () -> {
                Thread worker = Thread.currentThread();
                assertNotSame(firstOwner.get(), worker);
                assertNull(contamination.get(), "provider ThreadLocal escaped its disposable owner");
                assertNull(inherited.get(), "scanner owner inherited caller ThreadLocal state");
                assertSame(baselineLoader, worker.getContextClassLoader());
                assertSame(baselineHandler, worker.getUncaughtExceptionHandler());
                assertEquals(Thread.NORM_PRIORITY, worker.getPriority());
                assertFalse(worker.isInterrupted());
                assertTrue(worker.getName().startsWith("procwright-scanner-clean-"));
                return null;
            });
        } finally {
            inherited.remove();
            contamination.remove();
        }
        assertEquals(2, threadsCreated.get());
        assertEquals(1, owner.availablePermits());
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
    void abandonedProviderFailureIsReportedOnceAndCannotContaminateTheRecoveredOwner() throws Exception {
        ClassLoader baselineLoader = new ClassLoader(null) {};
        ClassLoader contaminatedLoader = new ClassLoader(null) {};
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch reported = new CountDownLatch(1);
        IllegalStateException lateFailure = new IllegalStateException("late provider failure");
        AtomicInteger reports = new AtomicInteger();
        AtomicReference<Thread> firstOwner = new AtomicReference<>();
        AtomicReference<String> baselineName = new AtomicReference<>();
        AtomicReference<String> reportedThreadName = new AtomicReference<>();
        BoundedFailureReporter failureReporter = new BoundedFailureReporter(1, 4);
        Thread.UncaughtExceptionHandler baselineHandler = (thread, failure) -> {
            if (failure == lateFailure) {
                reportedThreadName.set(thread.getName());
                reports.incrementAndGet();
                reported.countDown();
            }
        };
        ProcessProviderOperationOwner owner = new ProcessProviderOperationOwner(
                1,
                (threadPrefix, task) -> {
                    Thread thread = new Thread(null, task, threadPrefix + "1", 0, false);
                    baselineName.set(thread.getName());
                    thread.setContextClassLoader(baselineLoader);
                    thread.setUncaughtExceptionHandler(baselineHandler);
                    return thread;
                },
                failureReporter);

        CommandExecutionException timeout = assertThrows(
                CommandExecutionException.class,
                () -> owner.required("procwright-scanner-late-", Duration.ofMillis(25), () -> {
                    Thread worker = Thread.currentThread();
                    firstOwner.set(worker);
                    worker.setContextClassLoader(contaminatedLoader);
                    worker.setUncaughtExceptionHandler((thread, failure) -> {});
                    worker.setPriority(Thread.MIN_PRIORITY);
                    entered.countDown();
                    awaitUninterruptibly(release);
                    throw lateFailure;
                }));
        assertEquals(CommandExecutionException.Reason.RUNTIME_FAILURE, timeout.reason());
        assertTrue(ProcessProviderOperationOwner.causedByOperationDeadline(timeout));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertEquals(0, owner.availablePermits());
        try {
            assertThrows(
                    CommandExecutionException.class,
                    () -> owner.required("procwright-scanner-rejected-", Duration.ofSeconds(1), () -> null));
        } finally {
            release.countDown();
        }
        assertTrue(reported.await(1, TimeUnit.SECONDS));
        assertEquals(baselineName.get(), reportedThreadName.get());
        assertTrue(eventually(() -> owner.availablePermits() == 1));

        owner.required("procwright-scanner-recovered-", Duration.ofSeconds(1), () -> {
            Thread worker = Thread.currentThread();
            assertNotSame(firstOwner.get(), worker);
            assertSame(baselineLoader, worker.getContextClassLoader());
            assertSame(baselineHandler, worker.getUncaughtExceptionHandler());
            assertEquals(Thread.NORM_PRIORITY, worker.getPriority());
            assertFalse(worker.isInterrupted());
            return null;
        });
        assertTrue(failureReporter.awaitSettlement(Duration.ofSeconds(1)));
        assertEquals(1, reports.get());
    }

    @Test
    void lateFailureHandlerCannotMutateTheNextDisposableProviderOwner() throws Exception {
        ClassLoader baselineLoader = new ClassLoader(null) {};
        ClassLoader hostileLoader = new ClassLoader(null) {};
        CountDownLatch firstOperationEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstOperation = new CountDownLatch(1);
        CountDownLatch secondOperationEntered = new CountDownLatch(1);
        CountDownLatch handlerMutationCompleted = new CountDownLatch(1);
        CountDownLatch reported = new CountDownLatch(1);
        IllegalStateException lateFailure = new IllegalStateException("late disposable-owner failure");
        AtomicInteger reports = new AtomicInteger();
        AtomicReference<Thread> firstOwner = new AtomicReference<>();
        AtomicReference<Thread> reportedSource = new AtomicReference<>();
        AtomicReference<String> reportedSourceName = new AtomicReference<>();
        AtomicReference<ClassLoader> reportedSourceLoader = new AtomicReference<>();
        BoundedFailureReporter failureReporter = new BoundedFailureReporter(1, 4);
        Thread.UncaughtExceptionHandler hostileHandler = (thread, failure) -> {};
        Thread.UncaughtExceptionHandler baselineHandler = (source, failure) -> {
            if (failure != lateFailure) {
                return;
            }
            reportedSource.set(source);
            reportedSourceName.set(source.getName());
            reportedSourceLoader.set(source.getContextClassLoader());
            awaitUninterruptibly(secondOperationEntered);
            source.setName("hostile-reported-source");
            source.setContextClassLoader(hostileLoader);
            source.setUncaughtExceptionHandler(hostileHandler);
            source.setPriority(Thread.MIN_PRIORITY);
            source.interrupt();
            reports.incrementAndGet();
            handlerMutationCompleted.countDown();
            reported.countDown();
        };
        ProcessProviderOperationOwner owner = new ProcessProviderOperationOwner(
                1,
                (threadName, task) -> {
                    Thread thread = new Thread(null, task, threadName + "-source", 0, false);
                    thread.setContextClassLoader(baselineLoader);
                    thread.setUncaughtExceptionHandler(baselineHandler);
                    thread.setPriority(Thread.NORM_PRIORITY);
                    return thread;
                },
                failureReporter);
        ExecutorService caller = Executors.newSingleThreadExecutor();
        try {
            assertThrows(
                    CommandExecutionException.class,
                    () -> owner.required("procwright-scanner-first-", Duration.ofMillis(25), () -> {
                        firstOwner.set(Thread.currentThread());
                        firstOperationEntered.countDown();
                        awaitUninterruptibly(releaseFirstOperation);
                        throw lateFailure;
                    }));
            assertTrue(firstOperationEntered.await(1, TimeUnit.SECONDS));
            releaseFirstOperation.countDown();
            assertTrue(eventually(() -> owner.availablePermits() == 1));

            Future<WorkerObservation> second =
                    caller.submit(() -> owner.required("procwright-scanner-second-", Duration.ofSeconds(1), () -> {
                        Thread worker = Thread.currentThread();
                        secondOperationEntered.countDown();
                        awaitUninterruptibly(handlerMutationCompleted);
                        return new WorkerObservation(
                                worker,
                                worker.getName(),
                                worker.getContextClassLoader(),
                                worker.getUncaughtExceptionHandler(),
                                worker.getPriority(),
                                worker.isInterrupted());
                    }));

            WorkerObservation observation = second.get(1, TimeUnit.SECONDS);
            assertTrue(reported.await(1, TimeUnit.SECONDS));
            assertNotSame(firstOwner.get(), reportedSource.get());
            assertTrue(reportedSourceName.get().startsWith("procwright-scanner-first-"));
            assertSame(baselineLoader, reportedSourceLoader.get());
            assertNotSame(firstOwner.get(), observation.owner());
            assertTrue(observation.name().startsWith("procwright-scanner-second-"));
            assertSame(baselineLoader, observation.contextClassLoader());
            assertSame(baselineHandler, observation.uncaughtExceptionHandler());
            assertEquals(Thread.NORM_PRIORITY, observation.priority());
            assertFalse(observation.interrupted());
            assertTrue(failureReporter.awaitSettlement(Duration.ofSeconds(1)));
            assertEquals(1, reports.get());
        } finally {
            releaseFirstOperation.countDown();
            handlerMutationCompleted.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(1, TimeUnit.SECONDS));
        }
        assertEquals(1, owner.availablePermits());
    }

    @Test
    void ownerRecoversAfterThreadFactoryRejectionWithoutInvokingRejectedOperation() {
        AtomicInteger factoryCalls = new AtomicInteger();
        AtomicInteger operationCalls = new AtomicInteger();
        ProcessProviderOperationOwner owner = new ProcessProviderOperationOwner(
                1,
                (threadPrefix, task) -> {
                    if (factoryCalls.getAndIncrement() == 0) {
                        throw new SecurityException("scan owner denied");
                    }
                    return new Thread(task, threadPrefix + factoryCalls.get());
                },
                new BoundedFailureReporter(1, 4));

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
        BoundedFailureReporter failureReporter = new BoundedFailureReporter(1, 4);
        ProcessProviderOperationOwner owner = new ProcessProviderOperationOwner(
                1,
                (threadName, task) -> new Thread(null, task, threadName, 0, false) {
                    @Override
                    public synchronized void start() {
                        if (starts.getAndIncrement() == 0) {
                            throw expected;
                        }
                        super.start();
                    }
                },
                failureReporter);

        SecurityException actual = assertThrows(
                SecurityException.class,
                () -> owner.required("procwright-worker-start-denied-", Duration.ofSeconds(1), () -> {
                    operationCalls.incrementAndGet();
                    return null;
                }));

        assertSame(expected, actual);
        assertEquals(0, operationCalls.get());
        assertEquals(1, owner.availablePermits());
        assertTrue(failureReporter.awaitSettlement(Duration.ofSeconds(1)));

        owner.required("procwright-after-worker-start-denial-", Duration.ofSeconds(1), () -> {
            operationCalls.incrementAndGet();
            return null;
        });
        assertEquals(1, operationCalls.get());
        assertEquals(1, owner.availablePermits());
        assertTrue(failureReporter.awaitSettlement(Duration.ofSeconds(1)));
    }

    private static void assertWorkerCreationFailureRollsBack(SetupFailureKind failureKind) throws Exception {
        Throwable expected = failureKind.failure();
        AtomicInteger factoryCalls = new AtomicInteger();
        AtomicInteger operationCalls = new AtomicInteger();
        BoundedFailureReporter failureReporter = new BoundedFailureReporter(1, 4);
        ProcessProviderOperationOwner owner = new ProcessProviderOperationOwner(
                1,
                (threadPrefix, task) -> {
                    if (factoryCalls.getAndIncrement() == 0) {
                        throwUnchecked(expected);
                    }
                    return new Thread(task, threadPrefix + factoryCalls.get());
                },
                failureReporter);

        Throwable actual =
                captureFailure(() -> owner.required("procwright-worker-start-failure-", Duration.ofSeconds(1), () -> {
                    operationCalls.incrementAndGet();
                    return null;
                }));

        assertSame(expected, actual);
        assertEquals(0, operationCalls.get());
        assertEquals(1, owner.availablePermits());
        assertTrue(failureReporter.awaitSettlement(Duration.ofSeconds(1)));

        owner.required("procwright-after-worker-start-failure-", Duration.ofSeconds(1), () -> {
            operationCalls.incrementAndGet();
            return null;
        });
        assertEquals(1, operationCalls.get());
        assertEquals(1, owner.availablePermits());
        assertTrue(failureReporter.awaitSettlement(Duration.ofSeconds(1)));
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

    private record WorkerObservation(
            Thread owner,
            String name,
            ClassLoader contextClassLoader,
            Thread.UncaughtExceptionHandler uncaughtExceptionHandler,
            int priority,
            boolean interrupted) {}

    private record EmbeddedFailureValue(Throwable abandonedFailure)
            implements ProcessProviderOperationOwner.AbandonedFailureCarrier {}

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
