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
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class ProcessTreeScannerTest {

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

        ProcessTreeScanner.DescendantScan scan = scanner.scanDescendants(process, Duration.ofSeconds(1));
        Set<ProcessHandle> descendants = scan.handles();

        assertEquals(3, descendants.size());
        assertFalse(scan.complete());
        assertTrue(scan.truncated());
        assertFalse(scan.incomplete());
        assertEquals(4, produced.get());
        assertEquals(1, streamCloses.get());
        assertEquals(1, scanner.availableOperationPermits());
    }

    @Test
    void exactDescendantLimitIsCompleteWithoutReadingPastTheStream() {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 3, Duration.ofSeconds(1), Duration.ofMillis(50));
        AtomicInteger produced = new AtomicInteger();
        Process process = new StubProcess() {
            @Override
            public Stream<ProcessHandle> descendants() {
                return Stream.<ProcessHandle>generate(() -> new StubHandle(produced.incrementAndGet()))
                        .limit(3);
            }
        };

        ProcessTreeScanner.DescendantScan scan = scanner.scanDescendants(process, Duration.ofSeconds(1));

        assertEquals(3, scan.handles().size());
        assertTrue(scan.complete());
        assertFalse(scan.truncated());
        assertFalse(scan.incomplete());
        assertEquals(3, produced.get());
    }

    @Test
    void handleTraversalBoundsUniqueRootsAndChildrenRatherThanWrapperCount() {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 1, Duration.ofSeconds(1), Duration.ofMillis(50));
        ProcessHandle root = new StubHandle(710);
        ProcessHandle duplicateRoot = new StubHandle(710);
        ProcessHandle extraRoot = new StubHandle(711);

        assertTrue(scanner.scanDescendantsOfHandles(List.of(root), Duration.ofSeconds(1))
                .complete());
        assertTrue(scanner.scanDescendantsOfHandles(List.of(root, duplicateRoot), Duration.ofSeconds(1))
                .complete());
        assertTrue(scanner.scanDescendantsOfHandles(List.of(root, extraRoot), Duration.ofSeconds(1))
                .truncated());

        ProcessHandle child = new StubHandle(712);
        ProcessHandle duplicateChild = new StubHandle(712);
        ProcessHandle extraChild = new StubHandle(713);
        ProcessHandle exactChildren = handleWithChildren(714, child);
        ProcessHandle duplicateChildren = handleWithChildren(715, child, duplicateChild);
        ProcessHandle overflowChildren = handleWithChildren(716, child, extraChild);

        ProcessTreeScanner.DescendantScan exact =
                scanner.scanDescendantsOfHandles(List.of(exactChildren), Duration.ofSeconds(1));
        ProcessTreeScanner.DescendantScan duplicate =
                scanner.scanDescendantsOfHandles(List.of(duplicateChildren), Duration.ofSeconds(1));
        ProcessTreeScanner.DescendantScan overflow =
                scanner.scanDescendantsOfHandles(List.of(overflowChildren), Duration.ofSeconds(1));

        assertTrue(exact.complete());
        assertEquals(1, exact.handles().size());
        assertTrue(duplicate.complete());
        assertEquals(1, duplicate.handles().size());
        assertTrue(overflow.truncated());
        assertEquals(1, overflow.handles().size());
    }

    @Test
    void ordinaryEnumerationFailureDegradesToRootOnlyButFatalErrorRetainsIdentity() {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(50));
        IllegalStateException unavailable = new IllegalStateException("sysctl unavailable");
        AssertionError fatal = new AssertionError("fatal enumeration");
        ProcessHandle fatalPrefix = new StubHandle(700);
        Process fatalProcess = new StubProcess() {
            @Override
            public Stream<ProcessHandle> descendants() {
                return streamFailingAfter(fatalPrefix, fatal);
            }
        };

        ProcessTreeScanner.DescendantScan unavailableScan =
                scanner.scanDescendants(new ThrowingDescendantsProcess(unavailable), Duration.ofMillis(50));
        assertTrue(unavailableScan.handles().isEmpty());
        assertFalse(unavailableScan.complete());
        assertTrue(unavailableScan.incomplete());
        assertSame(ProcessTreeScanner.IncompleteReason.UNAVAILABLE, unavailableScan.incompleteReason());
        ProcessTreeScanner.DescendantScan fatalScan = scanner.scanDescendants(fatalProcess, Duration.ofMillis(50));
        AssertionError thrown = assertThrows(AssertionError.class, () -> scanner.descendants(fatalProcess));

        assertEquals(Set.of(fatalPrefix), fatalScan.handles());
        assertSame(fatal, fatalScan.failure());
        assertSame(fatal, thrown);
        assertEquals(1, scanner.availableOperationPermits());
    }

    @Test
    void timedOutHostileScanRetainsItsOnlyPermitUntilTheOperationActuallyReturns() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(25), Duration.ofMillis(25));
        BlockingDescendantsProcess blocked = new BlockingDescendantsProcess();
        FutureTask<ProcessTreeScanner.DescendantScan> scan =
                new FutureTask<>(() -> scanner.scanDescendants(blocked, Duration.ofMillis(25)));
        Thread worker = new Thread(scan, "process-tree-scan-test");
        worker.setDaemon(true);
        worker.start();
        try {
            assertTrue(blocked.entered.await(1, TimeUnit.SECONDS));
            ProcessTreeScanner.DescendantScan timedOut = scan.get(1, TimeUnit.SECONDS);
            assertTrue(timedOut.handles().isEmpty());
            assertTrue(timedOut.incomplete());
            assertSame(ProcessTreeScanner.IncompleteReason.CALLER_DEADLINE, timedOut.incompleteReason());
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
    void abandonedRootIndexingReportsItsEmbeddedFatalError() throws Exception {
        CountDownLatch indexingEntered = new CountDownLatch(1);
        CountDownLatch releaseIndexing = new CountDownLatch(1);
        CountDownLatch failureReported = new CountDownLatch(1);
        AtomicInteger reports = new AtomicInteger();
        AtomicReference<Throwable> reported = new AtomicReference<>();
        AssertionError fatal = new AssertionError("fatal root identity");
        BoundedFailureReporter failureReporter = new BoundedFailureReporter(1, 4);
        ProcessProviderOperationOwner owner = new ProcessProviderOperationOwner(
                1,
                (threadPrefix, task) -> {
                    Thread thread = new Thread(task, threadPrefix + "fatal-root");
                    thread.setUncaughtExceptionHandler((ignored, failure) -> {
                        reports.incrementAndGet();
                        reported.set(failure);
                        failureReported.countDown();
                    });
                    return thread;
                },
                failureReporter);
        ProcessTreeScanner scanner = new ProcessTreeScanner(owner, 4, Duration.ofMillis(25), Duration.ofMillis(25));
        ProcessHandle root = new StubHandle(728) {
            @Override
            public long pid() {
                indexingEntered.countDown();
                awaitUninterruptibly(releaseIndexing);
                throw fatal;
            }
        };

        try {
            ProcessTreeScanner.DescendantScan scan =
                    scanner.scanDescendantsOfHandles(List.of(root), Duration.ofMillis(25));

            assertTrue(indexingEntered.await(1, TimeUnit.SECONDS));
            assertTrue(scan.incomplete());
            assertSame(ProcessTreeScanner.IncompleteReason.CALLER_DEADLINE, scan.incompleteReason());
            assertEquals(0, scanner.availableOperationPermits());
        } finally {
            releaseIndexing.countDown();
        }

        assertTrue(failureReported.await(1, TimeUnit.SECONDS));
        assertSame(fatal, reported.get());
        assertEquals(1, reports.get());
        assertTrue(scanner.awaitReportingSettlement(Duration.ofSeconds(1)));
        assertEquals(1, scanner.availableOperationPermits());
    }

    @Test
    void deadlineShortenedStreamAndChildGraphScansAreIncomplete() throws Exception {
        assertDeadlineShortenedScanIsIncomplete(true);
        assertDeadlineShortenedScanIsIncomplete(false);
    }

    @Test
    void zeroBudgetCannotProveACompleteScan() {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(25));

        ProcessTreeScanner.DescendantScan processScan = scanner.scanDescendants(new StubProcess(), Duration.ZERO);
        ProcessTreeScanner.DescendantScan handleScan =
                scanner.scanDescendantsOfHandles(List.of(new StubHandle(720)), Duration.ZERO);

        assertTrue(processScan.incomplete());
        assertTrue(handleScan.incomplete());
        assertSame(ProcessTreeScanner.IncompleteReason.CALLER_DEADLINE, processScan.incompleteReason());
        assertSame(ProcessTreeScanner.IncompleteReason.CALLER_DEADLINE, handleScan.incompleteReason());
        assertFalse(processScan.complete());
        assertFalse(handleScan.complete());
    }

    @Test
    void knownSnapshotKeepsGuardedBoundaryAndCachedIdentity() {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(50));
        AtomicInteger pidCalls = new AtomicInteger();
        AtomicInteger infoCalls = new AtomicInteger();
        ProcessHandle delegate = new StubHandle(721) {
            @Override
            public long pid() {
                pidCalls.incrementAndGet();
                return super.pid();
            }

            @Override
            public Info info() {
                infoCalls.incrementAndGet();
                return super.info();
            }
        };
        ProcessHandle guarded = scanner.guardObserved(delegate);
        int identityPidCalls = pidCalls.get();
        int identityInfoCalls = infoCalls.get();

        KnownDescendants known = ProcessLifecycleSharedSupport.knownDescendants(guarded);

        ProcessHandle indexed = known.handles().iterator().next();
        assertTrue(indexed instanceof GuardedProcessHandle);
        assertSame(guarded, indexed);
        assertEquals(identityPidCalls, pidCalls.get());
        assertEquals(identityInfoCalls, infoCalls.get());
        assertEquals(ProcessTreeScanner.identity(guarded), ProcessTreeScanner.identity(indexed));
        assertEquals(identityPidCalls, pidCalls.get());
        assertEquals(identityInfoCalls, infoCalls.get());
    }

    @Test
    void hostileRootIdentityLookupIsDeadlineBounded() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(25), Duration.ofMillis(25));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ProcessHandle root = new StubHandle(722) {
            @Override
            public long pid() {
                entered.countDown();
                awaitUninterruptibly(release);
                return super.pid();
            }
        };
        FutureTask<ProcessTreeScanner.DescendantScan> task =
                new FutureTask<>(() -> scanner.scanDescendantsOfHandles(List.of(root), Duration.ofMillis(25)));
        Thread caller = new Thread(task, "hostile-root-identity-scan-test");
        caller.setDaemon(true);
        caller.start();
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            ProcessTreeScanner.DescendantScan scan = task.get(1, TimeUnit.SECONDS);
            assertTrue(scan.incomplete());
            assertSame(ProcessTreeScanner.IncompleteReason.CALLER_DEADLINE, scan.incompleteReason());
            assertTrue(scan.handles().isEmpty());
            assertEquals(0, scanner.availableOperationPermits());
        } finally {
            release.countDown();
            caller.join(TimeUnit.SECONDS.toMillis(1));
        }
        assertTrue(eventually(() -> scanner.availableOperationPermits() == 1));
    }

    @Test
    void internalScanTimeoutIsUnavailableRatherThanCallerDeadline() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(25), Duration.ofMillis(25));
        BlockingDescendantsProcess blocked = new BlockingDescendantsProcess();
        FutureTask<ProcessTreeScanner.DescendantScan> task =
                new FutureTask<>(() -> scanner.scanDescendants(blocked, Duration.ofSeconds(1)));
        Thread caller = new Thread(task, "internal-process-scan-timeout-test");
        caller.setDaemon(true);
        caller.start();
        try {
            assertTrue(blocked.entered.await(1, TimeUnit.SECONDS));
            ProcessTreeScanner.DescendantScan scan = task.get(1, TimeUnit.SECONDS);
            assertTrue(scan.incomplete());
            assertSame(ProcessTreeScanner.IncompleteReason.UNAVAILABLE, scan.incompleteReason());
            assertEquals(0, scanner.availableOperationPermits());
        } finally {
            blocked.release.countDown();
            caller.join(TimeUnit.SECONDS.toMillis(1));
        }
        assertTrue(eventually(() -> scanner.availableOperationPermits() == 1));
    }

    @Test
    void interruptedScanIsNotReportedAsProviderUnavailability() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofSeconds(1));
        BlockingDescendantsProcess blocked = new BlockingDescendantsProcess();
        AtomicReference<ProcessTreeScanner.DescendantScan> result = new AtomicReference<>();
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread caller = new Thread(() -> {
            result.set(scanner.scanDescendants(blocked, Duration.ofSeconds(1)));
            interrupted.set(Thread.currentThread().isInterrupted());
        });

        caller.start();
        try {
            assertTrue(blocked.entered.await(1, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(1));

            assertFalse(caller.isAlive());
            assertTrue(result.get().incomplete());
            assertSame(
                    ProcessTreeScanner.IncompleteReason.INTERRUPTED,
                    result.get().incompleteReason());
            assertTrue(interrupted.get());
        } finally {
            blocked.release.countDown();
            caller.join(TimeUnit.SECONDS.toMillis(1));
        }
        assertTrue(eventually(() -> scanner.availableOperationPermits() == 1));
    }

    @Test
    void ordinaryTraversalFailurePreservesTheObservedPrefix() {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofSeconds(1));
        ProcessHandle processChild = new StubHandle(723);
        Process process = new StubProcess() {
            @Override
            public Stream<ProcessHandle> descendants() {
                return streamFailingAfter(processChild);
            }
        };
        ProcessHandle graphChild = new StubHandle(724);
        ProcessHandle root = new StubHandle(725) {
            @Override
            public Stream<ProcessHandle> children() {
                return streamFailingAfter(graphChild);
            }
        };

        ProcessTreeScanner.DescendantScan processScan = scanner.scanDescendants(process, Duration.ofSeconds(1));
        ProcessTreeScanner.DescendantScan graphScan =
                scanner.scanDescendantsOfHandles(List.of(root), Duration.ofSeconds(1));

        assertEquals(Set.of(processChild), processScan.handles());
        assertTrue(processScan.incomplete());
        assertSame(ProcessTreeScanner.IncompleteReason.UNAVAILABLE, processScan.incompleteReason());
        assertEquals(Set.of(graphChild), graphScan.handles());
        assertTrue(graphScan.incomplete());
        assertSame(ProcessTreeScanner.IncompleteReason.UNAVAILABLE, graphScan.incompleteReason());
    }

    @Test
    void fatalChildrenInvocationPreservesEarlierRootPrefixAndStopsTraversal() {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofSeconds(1));
        ProcessHandle child = new StubHandle(726);
        AtomicInteger laterRootCalls = new AtomicInteger();
        ProcessHandle firstRoot = handleWithChildren(727, child);
        AssertionError fatal = new AssertionError("children failed");
        ProcessHandle failingRoot = new StubHandle(728) {
            @Override
            public Stream<ProcessHandle> children() {
                throw fatal;
            }
        };
        ProcessHandle unvisitedRoot = new StubHandle(729) {
            @Override
            public Stream<ProcessHandle> children() {
                laterRootCalls.incrementAndGet();
                return Stream.empty();
            }
        };

        ProcessTreeScanner.DescendantScan scan =
                scanner.scanDescendantsOfHandles(List.of(firstRoot, failingRoot, unvisitedRoot), Duration.ofSeconds(1));

        assertEquals(Set.of(child), scan.handles());
        assertSame(fatal, scan.failure());
        assertEquals(0, laterRootCalls.get());
    }

    @Test
    void fatalRootIndexingStopsBeforeChildTraversal() {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofSeconds(1));
        AtomicInteger childTraversalCalls = new AtomicInteger();
        ProcessHandle indexedRoot = new StubHandle(733) {
            @Override
            public Stream<ProcessHandle> children() {
                childTraversalCalls.incrementAndGet();
                return Stream.empty();
            }
        };
        AssertionError fatal = new AssertionError("root identity failed");
        ProcessHandle failingRoot = new StubHandle(734) {
            @Override
            public long pid() {
                throw fatal;
            }
        };

        ProcessTreeScanner.DescendantScan scan =
                scanner.scanDescendantsOfHandles(List.of(indexedRoot, failingRoot), Duration.ofSeconds(1));

        assertTrue(scan.incomplete());
        assertTrue(scan.handles().isEmpty());
        assertSame(fatal, scan.failure());
        assertEquals(0, childTraversalCalls.get());
    }

    @Test
    void embeddedFatalChildPrefixStopsBeforeTheNextRoot() {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofSeconds(1));
        ProcessHandle child = new StubHandle(730);
        AssertionError fatal = new AssertionError("child traversal failed");
        ProcessHandle failingRoot = new StubHandle(731) {
            @Override
            public Stream<ProcessHandle> children() {
                return streamFailingAfter(child, fatal);
            }
        };
        AtomicInteger laterRootCalls = new AtomicInteger();
        ProcessHandle unvisitedRoot = new StubHandle(732) {
            @Override
            public Stream<ProcessHandle> children() {
                laterRootCalls.incrementAndGet();
                return Stream.empty();
            }
        };

        ProcessTreeScanner.DescendantScan scan =
                scanner.scanDescendantsOfHandles(List.of(failingRoot, unvisitedRoot), Duration.ofSeconds(1));

        assertEquals(Set.of(child), scan.handles());
        assertSame(fatal, scan.failure());
        assertEquals(0, laterRootCalls.get());
    }

    @Test
    void provenRootOverflowSurvivesAChildScanTimeout() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 1, Duration.ofMillis(25), Duration.ofMillis(25));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ProcessHandle root = new StubHandle(726) {
            @Override
            public Stream<ProcessHandle> children() {
                entered.countDown();
                awaitUninterruptibly(release);
                return Stream.empty();
            }
        };
        FutureTask<ProcessTreeScanner.DescendantScan> task = new FutureTask<>(
                () -> scanner.scanDescendantsOfHandles(List.of(root, new StubHandle(727)), Duration.ofMillis(25)));
        Thread caller = new Thread(task, "truncated-root-child-timeout-test");
        caller.setDaemon(true);
        caller.start();
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            ProcessTreeScanner.DescendantScan scan = task.get(1, TimeUnit.SECONDS);
            assertTrue(scan.truncated());
            assertFalse(scan.incomplete());
        } finally {
            release.countDown();
            caller.join(TimeUnit.SECONDS.toMillis(1));
        }
        assertTrue(eventually(() -> scanner.availableOperationPermits() == 1));
    }

    @Test
    void longScanLoopUsesOneDisposableDaemonOwnerPerAcceptedOperation() {
        int capacity = 3;
        int scans = 512;
        AtomicInteger threadsCreated = new AtomicInteger();
        List<Thread> createdThreads = Collections.synchronizedList(new ArrayList<>());
        BoundedFailureReporter failureReporter = new BoundedFailureReporter(1, 4);
        ProcessProviderOperationOwner owner = new ProcessProviderOperationOwner(
                capacity,
                (threadName, task) -> {
                    int sequence = threadsCreated.incrementAndGet();
                    Thread thread = new Thread(null, task, threadName + "-" + sequence, 0, false);
                    createdThreads.add(thread);
                    return thread;
                },
                failureReporter);
        ProcessTreeScanner scanner = new ProcessTreeScanner(owner, 4, Duration.ofMillis(50), Duration.ofMillis(50));
        CountingDescendantsProcess process = new CountingDescendantsProcess();

        for (int index = 0; index < scans; index++) {
            assertTrue(scanner.descendants(process).isEmpty());
        }

        assertEquals(scans, process.calls.get());
        assertEquals(scans, threadsCreated.get());
        assertEquals((long) scans, createdThreads.stream().distinct().count());
        assertTrue(createdThreads.stream().allMatch(Thread::isDaemon), "scan owners must be daemon threads");
        assertEquals(capacity, scanner.availableOperationPermits());
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

    @Test
    void customPtyProviderProcessCannotCarryThreadLocalStateIntoALaterOperation() {
        ThreadLocal<Object> providerState = new ThreadLocal<>();
        Object retainedUserGraph = List.of(new byte[1_024]);
        AtomicInteger calls = new AtomicInteger();
        AtomicReference<Thread> firstWorker = new AtomicReference<>();
        Process supplied = new StubProcess() {
            @Override
            public boolean isAlive() {
                if (calls.getAndIncrement() == 0) {
                    assertNull(providerState.get());
                    firstWorker.set(Thread.currentThread());
                    providerState.set(retainedUserGraph);
                } else {
                    assertNotSame(firstWorker.get(), Thread.currentThread());
                    assertNull(providerState.get());
                }
                return true;
            }
        };
        PtyProvider provider = new PtyProvider() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String description() {
                return "thread-local contamination provider";
            }

            @Override
            public Process start(PtyRequest request) {
                return supplied;
            }
        };
        SessionExecutionPlan plan = sessionPlan(provider);
        Process started = ProcessTransport.resolve(plan).start(plan);

        assertTrue(started.isAlive());
        assertTrue(started.isAlive());
        assertEquals(2, calls.get());
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

    private static void assertDeadlineShortenedScanIsIncomplete(boolean streamTraversal) throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(25), Duration.ofMillis(25));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        FutureTask<ProcessTreeScanner.DescendantScan> scan;
        if (streamTraversal) {
            Process process = new StubProcess() {
                @Override
                public Stream<ProcessHandle> descendants() {
                    return Stream.generate(() -> {
                        entered.countDown();
                        awaitUninterruptibly(release);
                        return new StubHandle(701);
                    });
                }
            };
            scan = new FutureTask<>(() -> scanner.scanDescendants(process, Duration.ofMillis(25)));
        } else {
            ProcessHandle root = new StubHandle(702) {
                @Override
                public Stream<ProcessHandle> children() {
                    entered.countDown();
                    awaitUninterruptibly(release);
                    return Stream.empty();
                }
            };
            scan = new FutureTask<>(() -> scanner.scanDescendantsOfHandles(List.of(root), Duration.ofMillis(25)));
        }
        Thread caller = new Thread(scan, "deadline-shortened-descendant-scan-test");
        caller.setDaemon(true);
        caller.start();
        try {
            assertTrue(entered.await(1, TimeUnit.SECONDS));
            ProcessTreeScanner.DescendantScan result = scan.get(1, TimeUnit.SECONDS);
            assertTrue(result.handles().isEmpty());
            assertTrue(result.incomplete());
            assertSame(ProcessTreeScanner.IncompleteReason.CALLER_DEADLINE, result.incompleteReason());
            assertFalse(result.complete());
            assertFalse(result.truncated());
            assertEquals(0, scanner.availableOperationPermits());
        } finally {
            release.countDown();
            caller.join(TimeUnit.SECONDS.toMillis(1));
        }
        assertTrue(eventually(() -> scanner.availableOperationPermits() == 1));
    }

    private static ProcessHandle handleWithChildren(long pid, ProcessHandle... children) {
        return new StubHandle(pid) {
            @Override
            public Stream<ProcessHandle> children() {
                return Stream.of(children);
            }
        };
    }

    private static Stream<ProcessHandle> streamFailingAfter(ProcessHandle handle) {
        return streamFailingAfter(handle, new IllegalStateException("enumeration failed"));
    }

    private static Stream<ProcessHandle> streamFailingAfter(ProcessHandle handle, Throwable failure) {
        return Stream.concat(Stream.of(handle), Stream.generate(() -> {
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw (Error) failure;
        }));
    }

    private static Throwable captureFailure(ThrowingRunnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
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
