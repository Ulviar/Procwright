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
import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.PtyRequest;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import io.github.ulviar.procwright.terminal.TerminalSize;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

final class ProcessTreeScannerTest {

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
        ProcessTreeScanner scanner = new ProcessTreeScanner(
                1,
                4,
                Duration.ofMillis(30),
                Duration.ofMillis(30),
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
                    scanner.required("procwright-scanner-interruption-", Duration.ofMillis(30), () -> {
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
            assertTrue(eventually(() -> scanner.availableOperationPermits() == 1));
            assertTrue(scanner.awaitReportingSettlement(Duration.ofSeconds(1)));
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
        ProcessTreeScanner scanner = new ProcessTreeScanner(
                1,
                4,
                Duration.ofMillis(25),
                Duration.ofMillis(25),
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
                () -> scanner.required("procwright-scanner-error-", Duration.ofMillis(25), () -> {
                    entered.countDown();
                    awaitUninterruptibly(release);
                    throw lateError;
                }));
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        release.countDown();

        assertTrue(reported.await(1, TimeUnit.SECONDS));
        assertTrue(eventually(() -> scanner.availableOperationPermits() == 1));
        assertTrue(scanner.awaitReportingSettlement(Duration.ofSeconds(1)));
        assertEquals(1, reports.get());
    }

    @RepeatedTest(20)
    void reportingSettlementWaitsBetweenOperationCompletionAndReportRegistration() throws Exception {
        CountDownLatch operationEntered = new CountDownLatch(1);
        CountDownLatch releaseOperation = new CountDownLatch(1);
        CountDownLatch beforeReportSubmission = new CountDownLatch(1);
        CountDownLatch releaseReportSubmission = new CountDownLatch(1);
        CountDownLatch reported = new CountDownLatch(1);
        AssertionError lateError = new AssertionError("gated late provider error");
        AtomicInteger reports = new AtomicInteger();
        AtomicReference<Thread> providerOwner = new AtomicReference<>();
        BoundedFailureReporter failureReporter = new BoundedFailureReporter(1, 4);
        ProcessTreeScanner scanner = new ProcessTreeScanner(
                1,
                4,
                Duration.ofMillis(25),
                Duration.ofMillis(25),
                (threadPrefix, task) -> {
                    Thread thread = new Thread(null, task, threadPrefix + "settlement-race", 0, false);
                    thread.setUncaughtExceptionHandler((ignored, failure) -> {
                        if (failure == lateError) {
                            reports.incrementAndGet();
                            reported.countDown();
                        }
                    });
                    return thread;
                },
                failureReporter,
                () -> {
                    beforeReportSubmission.countDown();
                    awaitUninterruptibly(releaseReportSubmission);
                });
        FutureTask<Boolean> settlement =
                new FutureTask<>(() -> scanner.awaitReportingSettlement(Duration.ofSeconds(5)));
        Thread settlementWaiter = new Thread(settlement, "process-tree-reporting-settlement-test");
        settlementWaiter.setDaemon(true);
        try {
            assertThrows(
                    CommandExecutionException.class,
                    () -> scanner.required("procwright-scanner-gated-report-", Duration.ofMillis(25), () -> {
                        providerOwner.set(Thread.currentThread());
                        operationEntered.countDown();
                        awaitUninterruptibly(releaseOperation);
                        throw lateError;
                    }));
            assertTrue(operationEntered.await(1, TimeUnit.SECONDS));
            releaseOperation.countDown();
            assertTrue(beforeReportSubmission.await(1, TimeUnit.SECONDS));
            assertEquals(1, scanner.availableOperationPermits());

            settlementWaiter.start();
            assertTrue(eventually(() -> settlementWaiter.getState() == Thread.State.TIMED_WAITING));
            assertFalse(settlement.isDone());

            releaseReportSubmission.countDown();
            assertTrue(settlement.get(1, TimeUnit.SECONDS));
            settlementWaiter.join(TimeUnit.SECONDS.toMillis(1));
            assertFalse(settlementWaiter.isAlive());
            assertTrue(reported.await(1, TimeUnit.SECONDS));
            assertEquals(1, reports.get());

            scanner.required("procwright-scanner-after-gated-report-", Duration.ofSeconds(1), () -> {
                assertSame(providerOwner.get(), Thread.currentThread());
                assertFalse(Thread.currentThread().isInterrupted());
                return null;
            });
            assertTrue(scanner.awaitReportingSettlement(Duration.ofSeconds(1)));
            assertEquals(1, reports.get());
        } finally {
            releaseOperation.countDown();
            releaseReportSubmission.countDown();
            settlementWaiter.interrupt();
            settlementWaiter.join(TimeUnit.SECONDS.toMillis(1));
        }
    }

    @Test
    void validationFailuresReleaseTheAcquiredPermitWithoutCreatingAProducer() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofSeconds(1), Duration.ofSeconds(1));
        AtomicInteger operationCalls = new AtomicInteger();
        List<ThrowingRunnable> invalidInvocations = List.of(
                () -> scanner.required(null, Duration.ofSeconds(1), () -> {
                    operationCalls.incrementAndGet();
                    return null;
                }),
                () -> scanner.required("procwright-null-timeout-", null, () -> {
                    operationCalls.incrementAndGet();
                    return null;
                }),
                () -> scanner.required("procwright-null-operation-", Duration.ofSeconds(1), null));

        for (ThrowingRunnable invalidInvocation : invalidInvocations) {
            assertThrows(NullPointerException.class, invalidInvocation::run);
            assertEquals(1, scanner.availableOperationPermits());
            assertTrue(scanner.awaitReportingSettlement(Duration.ofMillis(100)));
        }
        assertEquals(0, operationCalls.get());

        assertEquals(
                "recovered",
                scanner.required("procwright-after-validation-failure-", Duration.ofSeconds(1), () -> "recovered"));
        assertEquals(1, scanner.availableOperationPermits());
        assertTrue(scanner.awaitReportingSettlement(Duration.ofSeconds(1)));
    }

    @Test
    void everySetupBoundaryRollsBackRuntimeErrorAndOutOfMemoryFailureWithoutOrphans() throws Exception {
        for (ProcessTreeScanner.SetupBoundary boundary : ProcessTreeScanner.SetupBoundary.values()) {
            for (SetupFailureKind failureKind : SetupFailureKind.values()) {
                assertSetupFailureRollsBack(boundary, failureKind);
            }
        }
    }

    private static void assertSetupFailureRollsBack(
            ProcessTreeScanner.SetupBoundary boundary, SetupFailureKind failureKind) throws Exception {
        AtomicInteger operationCalls = new AtomicInteger();
        AtomicInteger workerFailures = new AtomicInteger();
        AtomicInteger injections = new AtomicInteger();
        Throwable expected = failureKind.failureAt(boundary);
        BoundedFailureReporter failureReporter = new BoundedFailureReporter(1, 4);
        ProcessTreeScanner scanner = new ProcessTreeScanner(
                1,
                4,
                Duration.ofSeconds(1),
                Duration.ofSeconds(1),
                (threadPrefix, task) -> {
                    Thread thread = new Thread(null, task, threadPrefix + "setup-rollback", 0, false);
                    thread.setUncaughtExceptionHandler((ignored, failure) -> workerFailures.incrementAndGet());
                    return thread;
                },
                failureReporter,
                () -> {},
                observed -> {
                    if (observed == boundary && injections.getAndIncrement() == 0) {
                        throwUnchecked(expected);
                    }
                });

        Throwable actual =
                captureFailure(() -> scanner.required("procwright-setup-failure-", Duration.ofSeconds(1), () -> {
                    operationCalls.incrementAndGet();
                    return null;
                }));

        assertSame(expected, actual, () -> "wrong failure at " + boundary + " for " + failureKind);
        assertEquals(0, operationCalls.get(), () -> "orphan operation ran after " + boundary);
        assertEquals(1, scanner.availableOperationPermits(), () -> "permit not recovered after " + boundary);
        assertTrue(
                scanner.awaitReportingSettlement(Duration.ofSeconds(1)),
                () -> "producer not settled after " + boundary);

        scanner.required("procwright-after-setup-failure-", Duration.ofSeconds(1), () -> {
            operationCalls.incrementAndGet();
            return null;
        });
        assertEquals(1, operationCalls.get(), () -> "owner did not recover after " + boundary);
        assertEquals(1, scanner.availableOperationPermits(), () -> "permit over-released after " + boundary);
        assertTrue(scanner.awaitReportingSettlement(Duration.ofSeconds(1)));
        assertEquals(0, workerFailures.get(), () -> "rejected worker failed after " + boundary);
    }

    @Test
    void descendantScanIsIncrementalCountBoundedAndClosesItsStream() {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 3, Duration.ofSeconds(1), Duration.ofMillis(50));
        AtomicInteger produced = new AtomicInteger();
        AtomicInteger streamCloses = new AtomicInteger();
        Process process = new StubProcess() {
            @Override
            public Stream<ProcessHandle> descendants() {
                return Stream.<ProcessHandle>generate(() -> new StubHandle(produced.incrementAndGet()))
                        .limit(10)
                        .onClose(streamCloses::incrementAndGet);
            }
        };

        Set<ProcessHandle> descendants = scanner.descendants(process);

        assertEquals(3, descendants.size());
        assertEquals(3, produced.get());
        assertEquals(1, streamCloses.get());
        assertEquals(1, scanner.availableOperationPermits());
    }

    @Test
    void ordinaryEnumerationFailureDegradesToRootOnlyButFatalErrorRetainsIdentity() {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(50));
        IllegalStateException unavailable = new IllegalStateException("sysctl unavailable");
        AssertionError fatal = new AssertionError("fatal enumeration");

        assertTrue(
                scanner.descendants(new ThrowingDescendantsProcess(unavailable)).isEmpty());
        AssertionError thrown =
                assertThrows(AssertionError.class, () -> scanner.descendants(new ThrowingDescendantsProcess(fatal)));

        assertSame(fatal, thrown);
        assertEquals(1, scanner.availableOperationPermits());
    }

    @Test
    void timedOutHostileScanRetainsItsOnlyPermitUntilTheOperationActuallyReturns() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(25), Duration.ofMillis(25));
        BlockingDescendantsProcess blocked = new BlockingDescendantsProcess();
        FutureTask<Set<ProcessHandle>> scan = new FutureTask<>(() -> scanner.descendants(blocked));
        Thread worker = new Thread(scan, "process-tree-scan-test");
        worker.setDaemon(true);
        worker.start();
        try {
            assertTrue(blocked.entered.await(1, TimeUnit.SECONDS));
            assertTrue(scan.get(1, TimeUnit.SECONDS).isEmpty());
            assertEquals(0, scanner.availableOperationPermits());

            CountingDescendantsProcess rejected = new CountingDescendantsProcess();
            assertTrue(scanner.descendants(rejected).isEmpty());
            assertEquals(0, rejected.calls.get(), "capacity rejection must not invoke another provider process");
        } finally {
            blocked.release.countDown();
            worker.join(TimeUnit.SECONDS.toMillis(1));
        }
        assertTrue(eventually(() -> scanner.availableOperationPermits() == 1));

        CountingDescendantsProcess recovered = new CountingDescendantsProcess();
        assertTrue(scanner.descendants(recovered).isEmpty());
        assertEquals(1, recovered.calls.get(), "the released owner must accept later provider work");
    }

    @Test
    void longScanLoopReusesAtMostCapacityDaemonOwners() {
        int capacity = 3;
        int scans = 4_000;
        AtomicInteger threadsCreated = new AtomicInteger();
        List<Thread> createdThreads = Collections.synchronizedList(new ArrayList<>());
        ProcessTreeScanner scanner = new ProcessTreeScanner(
                capacity, 4, Duration.ofMillis(50), Duration.ofMillis(50), (threadPrefix, task) -> {
                    int sequence = threadsCreated.incrementAndGet();
                    Thread thread = new Thread(task, threadPrefix + sequence);
                    createdThreads.add(thread);
                    return thread;
                });
        CountingDescendantsProcess process = new CountingDescendantsProcess();

        for (int index = 0; index < scans; index++) {
            assertTrue(scanner.descendants(process).isEmpty());
        }

        assertEquals(scans, process.calls.get());
        assertTrue(threadsCreated.get() <= capacity, () -> "created " + threadsCreated.get() + " scan owners");
        assertTrue(createdThreads.stream().allMatch(Thread::isDaemon), "scan owners must be daemon threads");
        assertEquals(capacity, scanner.availableOperationPermits());
    }

    @Test
    void sharedSizedOwnerRunsThirtyTwoOperationsAndRejectsTheThirtyThirdWithoutQueueing() throws Exception {
        int capacity = ProcessTreeScanner.SHARED_OPERATION_CAPACITY;
        ProcessTreeScanner scanner = new ProcessTreeScanner(capacity, 4, Duration.ofSeconds(5), Duration.ofSeconds(5));
        CountDownLatch entered = new CountDownLatch(capacity);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger providerCalls = new AtomicInteger();
        Process blocked = new StubProcess() {
            @Override
            public Stream<ProcessHandle> descendants() {
                providerCalls.incrementAndGet();
                entered.countDown();
                awaitUninterruptibly(release);
                return Stream.empty();
            }
        };
        ExecutorService callers = Executors.newFixedThreadPool(capacity);
        List<Future<Set<ProcessHandle>>> scans = new ArrayList<>();
        try {
            for (int index = 0; index < capacity; index++) {
                scans.add(callers.submit(() -> scanner.descendants(blocked)));
            }
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            assertEquals(0, scanner.availableOperationPermits());

            CountingDescendantsProcess rejected = new CountingDescendantsProcess();
            long started = System.nanoTime();
            assertTrue(scanner.descendants(rejected).isEmpty());
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);
            assertEquals(0, rejected.calls.get());
            assertTrue(elapsed.compareTo(Duration.ofMillis(100)) < 0, () -> "capacity rejection took " + elapsed);
            CommandExecutionException requiredFailure = assertThrows(
                    CommandExecutionException.class,
                    () -> scanner.required("procwright-capacity-test-", Duration.ofSeconds(1), () -> "unreachable"));
            assertEquals(CommandExecutionException.Reason.RUNTIME_FAILURE, requiredFailure.reason());
            assertEquals(capacity, providerCalls.get());
        } finally {
            release.countDown();
            for (Future<Set<ProcessHandle>> scan : scans) {
                scan.get(2, TimeUnit.SECONDS);
            }
            callers.shutdownNow();
            assertTrue(callers.awaitTermination(1, TimeUnit.SECONDS));
        }
        assertTrue(eventually(() -> scanner.availableOperationPermits() == capacity));
    }

    @Test
    void reusableProviderOwnerDoesNotInheritCallerStateAndSanitizesSupportedThreadState() throws Exception {
        ClassLoader baselineLoader = new ClassLoader(null) {};
        ClassLoader contaminatedLoader = new ClassLoader(null) {};
        Thread.UncaughtExceptionHandler baselineHandler = (thread, failure) -> {};
        Thread.UncaughtExceptionHandler contaminatedHandler = (thread, failure) -> {};
        AtomicInteger threadsCreated = new AtomicInteger();
        AtomicReference<Thread> firstOwner = new AtomicReference<>();
        InheritableThreadLocal<String> inherited = new InheritableThreadLocal<>();
        inherited.set("caller-state");
        ProcessTreeScanner scanner =
                new ProcessTreeScanner(1, 4, Duration.ofSeconds(1), Duration.ofSeconds(1), (threadPrefix, task) -> {
                    Thread thread = new Thread(null, task, threadPrefix + threadsCreated.incrementAndGet(), 0, false);
                    thread.setContextClassLoader(baselineLoader);
                    thread.setUncaughtExceptionHandler(baselineHandler);
                    thread.setPriority(Thread.NORM_PRIORITY);
                    return thread;
                });
        try {
            scanner.required("procwright-scanner-dirty-", Duration.ofSeconds(1), () -> {
                Thread owner = Thread.currentThread();
                assertNull(inherited.get(), "scanner owner inherited caller ThreadLocal state");
                firstOwner.set(owner);
                owner.setContextClassLoader(contaminatedLoader);
                owner.setUncaughtExceptionHandler(contaminatedHandler);
                owner.setPriority(Thread.MIN_PRIORITY);
                owner.interrupt();
                return null;
            });

            scanner.required("procwright-scanner-clean-", Duration.ofSeconds(1), () -> {
                Thread owner = Thread.currentThread();
                assertSame(firstOwner.get(), owner, "capacity-one scanner should reuse its trusted owner");
                assertNull(inherited.get(), "scanner owner inherited caller ThreadLocal state");
                assertSame(baselineLoader, owner.getContextClassLoader());
                assertSame(baselineHandler, owner.getUncaughtExceptionHandler());
                assertEquals(Thread.NORM_PRIORITY, owner.getPriority());
                assertFalse(owner.isInterrupted());
                assertTrue(owner.getName().startsWith("procwright-scanner-clean-"));
                return null;
            });
        } finally {
            inherited.remove();
        }
        assertEquals(1, threadsCreated.get());
        assertEquals(1, scanner.availableOperationPermits());
    }

    @Test
    void productionProviderOwnerIsDaemonAndDoesNotInheritCallerThreadLocals() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofSeconds(1), Duration.ofSeconds(1));
        InheritableThreadLocal<String> inherited = new InheritableThreadLocal<>();
        inherited.set("caller-state");
        try {
            scanner.required("procwright-production-scanner-owner-", Duration.ofSeconds(1), () -> {
                assertNull(inherited.get());
                assertTrue(Thread.currentThread().isDaemon());
                return null;
            });
        } finally {
            inherited.remove();
        }
        assertEquals(1, scanner.availableOperationPermits());
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
        ProcessTreeScanner scanner = new ProcessTreeScanner(
                1,
                4,
                Duration.ofMillis(25),
                Duration.ofMillis(25),
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
                () -> scanner.required("procwright-scanner-late-", Duration.ofMillis(25), () -> {
                    Thread owner = Thread.currentThread();
                    firstOwner.set(owner);
                    owner.setContextClassLoader(contaminatedLoader);
                    owner.setUncaughtExceptionHandler((thread, failure) -> {});
                    owner.setPriority(Thread.MIN_PRIORITY);
                    entered.countDown();
                    awaitUninterruptibly(release);
                    throw lateFailure;
                }));
        assertEquals(CommandExecutionException.Reason.RUNTIME_FAILURE, timeout.reason());
        assertTrue(entered.await(1, TimeUnit.SECONDS));
        assertEquals(0, scanner.availableOperationPermits());
        try {
            assertThrows(
                    CommandExecutionException.class,
                    () -> scanner.required("procwright-scanner-rejected-", Duration.ofSeconds(1), () -> null));
        } finally {
            release.countDown();
        }
        assertTrue(reported.await(1, TimeUnit.SECONDS));
        assertEquals(baselineName.get(), reportedThreadName.get());
        assertTrue(eventually(() -> scanner.availableOperationPermits() == 1));

        scanner.required("procwright-scanner-recovered-", Duration.ofSeconds(1), () -> {
            Thread owner = Thread.currentThread();
            assertSame(firstOwner.get(), owner);
            assertSame(baselineLoader, owner.getContextClassLoader());
            assertSame(baselineHandler, owner.getUncaughtExceptionHandler());
            assertEquals(Thread.NORM_PRIORITY, owner.getPriority());
            assertFalse(owner.isInterrupted());
            return null;
        });
        assertTrue(scanner.awaitReportingSettlement(Duration.ofSeconds(1)));
        assertEquals(1, reports.get());
    }

    @Test
    void lateFailureHandlerCannotMutateAReusedProviderOwner() throws Exception {
        ClassLoader baselineLoader = new ClassLoader(null) {};
        ClassLoader hostileLoader = new ClassLoader(null) {};
        CountDownLatch firstOperationEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstOperation = new CountDownLatch(1);
        CountDownLatch secondOperationEntered = new CountDownLatch(1);
        CountDownLatch handlerMutationCompleted = new CountDownLatch(1);
        CountDownLatch reported = new CountDownLatch(1);
        IllegalStateException lateFailure = new IllegalStateException("late reusable-owner failure");
        AtomicInteger reports = new AtomicInteger();
        AtomicReference<Thread> reusableOwner = new AtomicReference<>();
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
        ProcessTreeScanner scanner = new ProcessTreeScanner(
                1,
                4,
                Duration.ofMillis(25),
                Duration.ofMillis(25),
                (threadPrefix, task) -> {
                    Thread thread = new Thread(null, task, threadPrefix + "detached-source", 0, false);
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
                    () -> scanner.required("procwright-scanner-first-", Duration.ofMillis(25), () -> {
                        reusableOwner.set(Thread.currentThread());
                        firstOperationEntered.countDown();
                        awaitUninterruptibly(releaseFirstOperation);
                        throw lateFailure;
                    }));
            assertTrue(firstOperationEntered.await(1, TimeUnit.SECONDS));
            releaseFirstOperation.countDown();
            assertTrue(eventually(() -> scanner.availableOperationPermits() == 1));

            Future<WorkerObservation> second =
                    caller.submit(() -> scanner.required("procwright-scanner-second-", Duration.ofSeconds(1), () -> {
                        Thread owner = Thread.currentThread();
                        secondOperationEntered.countDown();
                        awaitUninterruptibly(handlerMutationCompleted);
                        return new WorkerObservation(
                                owner,
                                owner.getName(),
                                owner.getContextClassLoader(),
                                owner.getUncaughtExceptionHandler(),
                                owner.getPriority(),
                                owner.isInterrupted());
                    }));

            WorkerObservation observation = second.get(1, TimeUnit.SECONDS);
            assertTrue(reported.await(1, TimeUnit.SECONDS));
            assertNotSame(reusableOwner.get(), reportedSource.get());
            assertEquals("procwright-process-operation-owner-0-detached-source", reportedSourceName.get());
            assertSame(baselineLoader, reportedSourceLoader.get());
            assertSame(reusableOwner.get(), observation.owner());
            assertTrue(observation.name().startsWith("procwright-scanner-second-"));
            assertSame(baselineLoader, observation.contextClassLoader());
            assertSame(baselineHandler, observation.uncaughtExceptionHandler());
            assertEquals(Thread.NORM_PRIORITY, observation.priority());
            assertFalse(observation.interrupted());
            assertTrue(scanner.awaitReportingSettlement(Duration.ofSeconds(1)));
            assertEquals(1, reports.get());
        } finally {
            releaseFirstOperation.countDown();
            handlerMutationCompleted.countDown();
            caller.shutdownNow();
            assertTrue(caller.awaitTermination(1, TimeUnit.SECONDS));
        }
        assertEquals(1, scanner.availableOperationPermits());
    }

    @Test
    void scannerRecoversAfterThreadFactoryRejectionWithoutInvokingRejectedOperation() {
        AtomicInteger factoryCalls = new AtomicInteger();
        AtomicInteger operationCalls = new AtomicInteger();
        ProcessTreeScanner scanner =
                new ProcessTreeScanner(1, 4, Duration.ofMillis(50), Duration.ofMillis(50), (threadPrefix, task) -> {
                    if (factoryCalls.getAndIncrement() == 0) {
                        throw new SecurityException("scan owner denied");
                    }
                    return new Thread(task, threadPrefix + factoryCalls.get());
                });
        Process process = new StubProcess() {
            @Override
            public Stream<ProcessHandle> descendants() {
                operationCalls.incrementAndGet();
                return Stream.empty();
            }
        };

        assertTrue(scanner.descendants(process).isEmpty());
        assertEquals(0, operationCalls.get());
        assertEquals(1, scanner.availableOperationPermits());

        assertTrue(scanner.descendants(process).isEmpty());
        assertEquals(1, operationCalls.get());
        assertEquals(1, scanner.availableOperationPermits());
    }

    @Test
    void everyProviderProcessOperationIsDeadlineBoundedAndRetainsCapacityUntilActualReturn() throws Exception {
        for (BlockedOperation operation : BlockedOperation.values()) {
            ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(25), Duration.ofMillis(25));
            BlockingOperationProcess delegate = new BlockingOperationProcess(operation);
            Process guarded = scanner.guard(delegate);
            FutureTask<Throwable> invocation = new FutureTask<>(() -> captureFailure(() -> operation.invoke(guarded)));
            Thread caller = new Thread(invocation, "guarded-process-operation-test");
            caller.setDaemon(true);
            caller.start();
            try {
                assertTrue(delegate.entered.await(1, TimeUnit.SECONDS), "operation did not enter: " + operation);
                Throwable failure = invocation.get(1, TimeUnit.SECONDS);
                if (operation == BlockedOperation.DESCENDANTS) {
                    assertSame(null, failure);
                } else {
                    assertTrue(failure instanceof CommandExecutionException, () -> operation + " returned " + failure);
                    assertEquals(
                            CommandExecutionException.Reason.RUNTIME_FAILURE,
                            ((CommandExecutionException) failure).reason());
                }
                assertEquals(0, scanner.availableOperationPermits(), "operation permit leaked early: " + operation);
            } finally {
                delegate.release.countDown();
                caller.join(TimeUnit.SECONDS.toMillis(1));
            }
            assertFalse(caller.isAlive(), "guarded caller did not terminate: " + operation);
            assertTrue(eventually(() -> scanner.availableOperationPermits() == 1));
            if (operation == BlockedOperation.WAIT_FOR) {
                assertTrue(
                        delegate.waitTimeoutNanos.get() <= Duration.ofMillis(25).toNanos());
            }
        }
    }

    @Test
    void providerOperationFatalErrorRetainsExactIdentity() {
        AssertionError expected = new AssertionError("provider liveness failed");
        Process delegate = new StubProcess() {
            @Override
            public boolean isAlive() {
                throw expected;
            }
        };
        Process guarded = new ProcessTreeScanner(1, 4, Duration.ofMillis(50)).guard(delegate);

        AssertionError actual = assertThrows(AssertionError.class, guarded::isAlive);

        assertSame(expected, actual);
    }

    @Test
    void everyProviderHandleOperationUsesTheSameBoundedOwnerUntilActualReturn() throws Exception {
        for (BlockedHandleOperation operation : BlockedHandleOperation.values()) {
            ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(25), Duration.ofMillis(25));
            BlockingOperationHandle delegate = new BlockingOperationHandle(operation);
            ProcessHandle guarded = scanner.guardObserved(delegate);
            FutureTask<Throwable> invocation = new FutureTask<>(() -> captureFailure(() -> operation.invoke(guarded)));
            Thread caller = new Thread(invocation, "guarded-process-handle-operation-test");
            caller.setDaemon(true);
            caller.start();
            try {
                assertTrue(delegate.entered.await(1, TimeUnit.SECONDS), "operation did not enter: " + operation);
                Throwable failure = invocation.get(1, TimeUnit.SECONDS);
                if (operation.bestEffort()) {
                    assertSame(null, failure);
                } else {
                    assertTrue(failure instanceof CommandExecutionException, () -> operation + " returned " + failure);
                }
                assertEquals(0, scanner.availableOperationPermits(), "operation permit leaked early: " + operation);
            } finally {
                delegate.release.countDown();
                caller.join(TimeUnit.SECONDS.toMillis(1));
            }
            assertFalse(caller.isAlive(), "guarded handle caller did not terminate: " + operation);
            assertTrue(eventually(() -> scanner.availableOperationPermits() == 1));
        }
    }

    @Test
    void successfulCustomPtyProviderProcessIsGuardedBeforeRuntimePublication() {
        Process supplied = new StubProcess();
        PtyProvider provider = new PtyProvider() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String description() {
                return "test provider";
            }

            @Override
            public Process start(PtyRequest request) {
                return supplied;
            }
        };

        Process started = ProcessTransport.resolve(sessionPlan(provider)).start(sessionPlan(provider));

        assertTrue(started instanceof GuardedProcess);
        assertSame(supplied, ((GuardedProcess) started).delegate());
    }

    private static SessionExecutionPlan sessionPlan(PtyProvider provider) {
        LaunchPlan launch = new LaunchPlan(
                LaunchMode.DIRECT,
                List.of("test"),
                Optional.empty(),
                EnvironmentPolicy.INHERIT,
                Map.of(),
                OutputMode.SEPARATE,
                TerminalPolicy.REQUIRED);
        return new SessionExecutionPlan(
                launch,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                Duration.ZERO,
                StandardCharsets.UTF_8,
                provider,
                TerminalSize.defaults());
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

    private enum SetupFailureKind {
        RUNTIME_EXCEPTION {
            @Override
            Throwable failureAt(ProcessTreeScanner.SetupBoundary boundary) {
                return new IllegalStateException("injected setup failure at " + boundary);
            }
        },
        ERROR {
            @Override
            Throwable failureAt(ProcessTreeScanner.SetupBoundary boundary) {
                return new AssertionError("injected setup error at " + boundary);
            }
        },
        OUT_OF_MEMORY_ERROR {
            @Override
            Throwable failureAt(ProcessTreeScanner.SetupBoundary boundary) {
                return new OutOfMemoryError("injected setup allocation failure at " + boundary);
            }
        };

        abstract Throwable failureAt(ProcessTreeScanner.SetupBoundary boundary);
    }

    private enum BlockedOperation {
        STDIN {
            @Override
            void invoke(Process process) {
                process.getOutputStream();
            }
        },
        STDOUT {
            @Override
            void invoke(Process process) {
                process.getInputStream();
            }
        },
        STDERR {
            @Override
            void invoke(Process process) {
                process.getErrorStream();
            }
        },
        IS_ALIVE {
            @Override
            void invoke(Process process) {
                process.isAlive();
            }
        },
        WAIT_FOR {
            @Override
            void invoke(Process process) throws InterruptedException {
                process.waitFor(1, TimeUnit.SECONDS);
            }
        },
        TO_HANDLE {
            @Override
            void invoke(Process process) {
                process.toHandle();
            }
        },
        DESCENDANTS {
            @Override
            void invoke(Process process) {
                assertTrue(process.descendants().toList().isEmpty());
            }
        };

        abstract void invoke(Process process) throws Exception;
    }

    private enum BlockedHandleOperation {
        IS_ALIVE(false) {
            @Override
            void invoke(ProcessHandle handle) {
                handle.isAlive();
            }
        },
        DESTROY(false) {
            @Override
            void invoke(ProcessHandle handle) {
                handle.destroy();
            }
        },
        FORCE_DESTROY(false) {
            @Override
            void invoke(ProcessHandle handle) {
                handle.destroyForcibly();
            }
        },
        CHILDREN(true) {
            @Override
            void invoke(ProcessHandle handle) {
                handle.children().toList();
            }
        },
        DESCENDANTS(true) {
            @Override
            void invoke(ProcessHandle handle) {
                handle.descendants().toList();
            }
        };

        private final boolean bestEffort;

        BlockedHandleOperation(boolean bestEffort) {
            this.bestEffort = bestEffort;
        }

        boolean bestEffort() {
            return bestEffort;
        }

        abstract void invoke(ProcessHandle handle) throws Exception;
    }

    private static class StubProcess extends Process {

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            return InputStream.nullInputStream();
        }

        @Override
        public int waitFor() {
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return true;
        }

        @Override
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {}

        @Override
        public Process destroyForcibly() {
            return this;
        }

        @Override
        public boolean isAlive() {
            return false;
        }

        @Override
        public long pid() {
            return 101L;
        }

        @Override
        public ProcessHandle toHandle() {
            return new StubHandle(pid());
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }

    private static final class ThrowingDescendantsProcess extends StubProcess {

        private final Throwable failure;

        private ThrowingDescendantsProcess(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw (Error) failure;
        }
    }

    private static final class BlockingDescendantsProcess extends StubProcess {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public Stream<ProcessHandle> descendants() {
            entered.countDown();
            awaitUninterruptibly(release);
            return Stream.empty();
        }
    }

    private static final class CountingDescendantsProcess extends StubProcess {

        private final AtomicInteger calls = new AtomicInteger();

        @Override
        public Stream<ProcessHandle> descendants() {
            calls.incrementAndGet();
            return Stream.empty();
        }
    }

    private static final class BlockingOperationProcess extends StubProcess {

        private final BlockedOperation blockedOperation;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicLong waitTimeoutNanos = new AtomicLong(Long.MAX_VALUE);

        private BlockingOperationProcess(BlockedOperation blockedOperation) {
            this.blockedOperation = blockedOperation;
        }

        @Override
        public OutputStream getOutputStream() {
            block(BlockedOperation.STDIN);
            return super.getOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            block(BlockedOperation.STDOUT);
            return super.getInputStream();
        }

        @Override
        public InputStream getErrorStream() {
            block(BlockedOperation.STDERR);
            return super.getErrorStream();
        }

        @Override
        public boolean isAlive() {
            block(BlockedOperation.IS_ALIVE);
            return super.isAlive();
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            waitTimeoutNanos.set(unit.toNanos(timeout));
            block(BlockedOperation.WAIT_FOR);
            return true;
        }

        @Override
        public ProcessHandle toHandle() {
            block(BlockedOperation.TO_HANDLE);
            return super.toHandle();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            block(BlockedOperation.DESCENDANTS);
            return Stream.empty();
        }

        private void block(BlockedOperation operation) {
            if (operation == blockedOperation) {
                entered.countDown();
                awaitUninterruptibly(release);
            }
        }
    }

    private static class StubHandle implements ProcessHandle {

        private final long pid;

        private StubHandle(long pid) {
            this.pid = pid;
        }

        @Override
        public long pid() {
            return pid;
        }

        @Override
        public Optional<ProcessHandle> parent() {
            return Optional.empty();
        }

        @Override
        public Stream<ProcessHandle> children() {
            return Stream.empty();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        @Override
        public Info info() {
            return ProcessHandle.current().info();
        }

        @Override
        public CompletableFuture<ProcessHandle> onExit() {
            return CompletableFuture.completedFuture(this);
        }

        @Override
        public boolean supportsNormalTermination() {
            return true;
        }

        @Override
        public boolean destroy() {
            return true;
        }

        @Override
        public boolean destroyForcibly() {
            return true;
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public int compareTo(ProcessHandle other) {
            return Long.compare(pid, other.pid());
        }
    }

    private static final class BlockingOperationHandle extends StubHandle {

        private final BlockedHandleOperation blockedOperation;
        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        private BlockingOperationHandle(BlockedHandleOperation blockedOperation) {
            super(102L);
            this.blockedOperation = blockedOperation;
        }

        @Override
        public Stream<ProcessHandle> children() {
            block(BlockedHandleOperation.CHILDREN);
            block(BlockedHandleOperation.DESCENDANTS);
            return Stream.empty();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            throw new AssertionError("guarded traversal must use incremental children(), not transitive descendants()");
        }

        @Override
        public boolean destroy() {
            block(BlockedHandleOperation.DESTROY);
            return true;
        }

        @Override
        public boolean destroyForcibly() {
            block(BlockedHandleOperation.FORCE_DESTROY);
            return true;
        }

        @Override
        public boolean isAlive() {
            block(BlockedHandleOperation.IS_ALIVE);
            return true;
        }

        private void block(BlockedHandleOperation operation) {
            if (operation == blockedOperation) {
                entered.countDown();
                awaitUninterruptibly(release);
            }
        }
    }
}
