/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.ProcessTreeScannerStubs.StubHandle;
import io.github.ulviar.procwright.internal.ProcessTreeScannerStubs.StubProcess;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class ProcessTreeScannerTraversalTest {

    @Test
    void descendantScanIsIncrementalCountBoundedAndClosesItsStream() {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 3, Duration.ofSeconds(1));
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
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 3, Duration.ofSeconds(1));
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
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 1, Duration.ofSeconds(1));
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
    void traversalAndStreamCloseFailuresProduceADetachedAggregateWithoutMutatingEitherSource() {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(50));
        ProcessHandle observedChild = new StubHandle(701);

        for (Throwable closeFailure :
                List.of(new IllegalStateException("stream close failed"), new AssertionError("fatal stream close"))) {
            AssertionError traversalFailure = new AssertionError("fatal enumeration");
            Process process = new StubProcess() {
                @Override
                public Stream<ProcessHandle> descendants() {
                    return streamFailingAfter(observedChild, traversalFailure).onClose(() -> rethrow(closeFailure));
                }
            };

            ProcessTreeScanner.DescendantScan scan = scanner.scanDescendants(process, Duration.ofMillis(50));

            assertEquals(Set.of(observedChild), scan.handles());
            assertTrue(scan.incomplete());
            assertTrue(scan.failure() instanceof Error);
            assertNotSame(traversalFailure, scan.failure());
            assertSame(traversalFailure, FailureAggregation.primary(scan.failure()));
            assertEquals(List.of(traversalFailure, closeFailure), FailureAggregation.sources(scan.failure()));
            assertEquals(0, traversalFailure.getSuppressed().length);
            assertEquals(0, closeFailure.getSuppressed().length);
        }
    }

    @Test
    void fatalStreamCloseBecomesPrimaryWithoutReorderingOrMutatingTraversalSources() {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(50));
        ProcessHandle observedChild = new StubHandle(702);
        IllegalStateException traversalFailure = new IllegalStateException("enumeration failed");
        AssertionError closeFailure = new AssertionError("fatal stream close");
        Process process = new StubProcess() {
            @Override
            public Stream<ProcessHandle> descendants() {
                return streamFailingAfter(observedChild, traversalFailure).onClose(() -> rethrow(closeFailure));
            }
        };

        ProcessTreeScanner.DescendantScan scan = scanner.scanDescendants(process, Duration.ofMillis(50));

        assertEquals(Set.of(observedChild), scan.handles());
        assertTrue(scan.incomplete());
        assertTrue(scan.failure() instanceof Error);
        assertSame(closeFailure, FailureAggregation.primary(scan.failure()));
        assertEquals(List.of(traversalFailure, closeFailure), FailureAggregation.sources(scan.failure()));
        assertEquals(0, traversalFailure.getSuppressed().length);
        assertEquals(0, closeFailure.getSuppressed().length);
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
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 1, Duration.ofMillis(25));
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

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
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
}
