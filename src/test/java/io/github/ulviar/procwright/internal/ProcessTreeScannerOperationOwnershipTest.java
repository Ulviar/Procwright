/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.internal.ProcessTreeScannerStubs.StubHandle;
import io.github.ulviar.procwright.internal.ProcessTreeScannerStubs.StubProcess;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class ProcessTreeScannerOperationOwnershipTest {

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
