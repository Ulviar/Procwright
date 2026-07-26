/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.knownDescendants;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.ProcessTreeScannerStubs.StubHandle;
import io.github.ulviar.procwright.internal.ProcessTreeScannerStubs.StubProcess;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class ProcessTreeScannerOutcomeClassificationTest {

    @Test
    void deadlineShortenedStreamAndChildGraphScansAreIncomplete() throws Exception {
        assertDeadlineShortenedScanIsIncomplete(true);
        assertDeadlineShortenedScanIsIncomplete(false);
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

        KnownDescendants known = knownDescendants(guarded);

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
}
