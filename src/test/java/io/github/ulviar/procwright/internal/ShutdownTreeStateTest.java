/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.MutableProcessHandle;
import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.failureSources;
import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.knownDescendants;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class ShutdownTreeStateTest {

    private static final Duration FULL_LIMIT_DISCOVERY_BUDGET = Duration.ofSeconds(5);

    @Test
    void newlyDiscoveredDescendantBecomesPendingExactlyOnce() {
        MutableProcessHandle known = new MutableProcessHandle(401);
        MutableProcessHandle discovered = new MutableProcessHandle(402);
        SequencedDescendantProcess process = new SequencedDescendantProcess(discovered);
        ShutdownFailureLedger failures = new ShutdownFailureLedger();
        ShutdownTreeState state = new ShutdownTreeState(process, failures);
        state.initialize(knownDescendants(known), Duration.ofSeconds(1));

        boolean changed = state.discoverPending(Duration.ofSeconds(1));

        assertEquals(true, changed);
        assertEquals(Set.of(discovered), state.takePendingDescendants());
        assertEquals(Set.of(), state.takePendingDescendants());
        assertEquals(List.of(known, discovered), state.takeAllDescendants());
    }

    @Test
    void completionExclusionRemovesAFailedHandleFromTheProofSet() {
        MutableProcessHandle live = new MutableProcessHandle(403);
        ShutdownFailureLedger failures = new ShutdownFailureLedger();
        ShutdownTreeState state = new ShutdownTreeState(new EmptyDescendantProcess(), failures);
        state.initialize(knownDescendants(live), Duration.ofSeconds(1));

        assertSame(ShutdownTreeState.DescendantState.LIVE, state.observeDescendants());

        state.excludeFromCompletion(live);

        assertSame(ShutdownTreeState.DescendantState.EXITED, state.observeDescendants());
    }

    @Test
    void knownDescendantOverflowRetainsOrderedPrefixAndRecordsFailure() {
        int limit = ProcessTreeScanner.shared().descendantLimit();
        List<MutableProcessHandle> handles = handles(limit + 1, 1_000);
        ShutdownFailureLedger failures = new ShutdownFailureLedger();
        ShutdownTreeState state = new ShutdownTreeState(new EmptyDescendantProcess(), failures);

        state.initialize(knownDescendants(new LinkedHashSet<>(handles)), FULL_LIMIT_DISCOVERY_BUDGET);

        List<ProcessHandle> descendants = state.takeAllDescendants();
        assertEquals(limit, descendants.size());
        assertSame(handles.get(0), descendants.get(0));
        assertSame(handles.get(limit - 1), descendants.get(descendants.size() - 1));
        CommandExecutionException failure = assertThrows(CommandExecutionException.class, failures::rethrowIfPresent);
        assertTrue(failure.getMessage().contains("bounded descendant limit"));
    }

    @Test
    void exactKnownDescendantLimitIsAcceptedWithoutFailure() {
        int limit = ProcessTreeScanner.shared().descendantLimit();
        List<MutableProcessHandle> handles = handles(limit, 5_000);
        ShutdownFailureLedger failures = new ShutdownFailureLedger();
        ShutdownTreeState state = new ShutdownTreeState(new EmptyDescendantProcess(), failures);

        state.initialize(knownDescendants(new LinkedHashSet<>(handles)), FULL_LIMIT_DISCOVERY_BUDGET);

        assertEquals(handles, state.takeAllDescendants());
        failures.rethrowIfPresent();
    }

    @Test
    void repeatedWrapperAtExactLimitDoesNotCreateOverflowAcrossScans() {
        int limit = ProcessTreeScanner.shared().descendantLimit();
        List<MutableProcessHandle> handles = handles(limit, 30_000);
        MutableProcessHandle repeated =
                new MutableProcessHandle(handles.get(limit - 1).pid());
        ShutdownFailureLedger failures = new ShutdownFailureLedger();
        ShutdownTreeState state = new ShutdownTreeState(new SequencedDescendantProcess(repeated), failures);
        state.initialize(knownDescendants(new LinkedHashSet<>(handles)), FULL_LIMIT_DISCOVERY_BUDGET);

        boolean discovered = state.discoverPending(FULL_LIMIT_DISCOVERY_BUDGET);

        assertEquals(false, discovered);
        assertEquals(limit, state.takeAllDescendants().size());
        failures.rethrowIfPresent();
    }

    @Test
    void pendingSetContainsOnlyDescendantsAcceptedByTheBoundedTree() {
        int limit = ProcessTreeScanner.shared().descendantLimit();
        List<MutableProcessHandle> initial = handles(limit - 1, 10_000);
        MutableProcessHandle accepted = new MutableProcessHandle(20_000);
        MutableProcessHandle overflow = new MutableProcessHandle(20_001);
        SequencedDescendantProcess process = new SequencedDescendantProcess(accepted, overflow);
        ShutdownFailureLedger failures = new ShutdownFailureLedger();
        ShutdownTreeState state = new ShutdownTreeState(process, failures);
        state.initialize(knownDescendants(new LinkedHashSet<>(initial)), FULL_LIMIT_DISCOVERY_BUDGET);

        state.discoverPending(FULL_LIMIT_DISCOVERY_BUDGET);

        assertEquals(Set.of(accepted), state.takePendingDescendants());
        assertEquals(limit, state.takeAllDescendants().size());
        initial.forEach(ProcessHandle::destroyForcibly);
        accepted.destroyForcibly();
        assertSame(ShutdownTreeState.DescendantState.UNOBSERVABLE, state.observeDescendants());
        CommandExecutionException failure = assertThrows(CommandExecutionException.class, failures::rethrowIfPresent);
        assertTrue(failure.getMessage().contains("bounded descendant limit"));
    }

    @Test
    void fatalDiscoveryFailureRetainsIdentity() {
        AssertionError expected = new AssertionError("discovery failed");
        ShutdownFailureLedger failures = new ShutdownFailureLedger();
        ShutdownTreeState state = new ShutdownTreeState(new FailingDescendantProcess(expected), failures);

        state.initialize(KnownDescendants.empty(), Duration.ofSeconds(1));

        Error actual = assertThrows(Error.class, failures::rethrowIfPresent);
        assertTrue(failureSources(actual).contains(expected));
    }

    @Test
    void fatalDiscoveryRetainsItsObservedPrefixForCleanup() {
        MutableProcessHandle observed = new MutableProcessHandle(405);
        AssertionError expected = new AssertionError("discovery failed after prefix");
        ShutdownFailureLedger failures = new ShutdownFailureLedger();
        ShutdownTreeState state =
                new ShutdownTreeState(new PrefixFailingDescendantProcess(observed, expected), failures);

        state.initialize(KnownDescendants.empty(), Duration.ofSeconds(1));

        assertEquals(List.of(observed), state.takeAllDescendants());
        Error actual = assertThrows(Error.class, failures::rethrowIfPresent);
        assertTrue(failureSources(actual).contains(expected));
    }

    @Test
    void unavailableDiscoveryRecordsFailureAndCannotProveCompletion() {
        ShutdownFailureLedger failures = new ShutdownFailureLedger();
        ShutdownTreeState state =
                new ShutdownTreeState(new FailingDescendantProcess(new IllegalStateException("unavailable")), failures);

        state.initialize(KnownDescendants.empty(), Duration.ofSeconds(1));

        assertSame(ShutdownTreeState.DescendantState.UNOBSERVABLE, state.observeDescendants());
        CommandExecutionException failure = assertThrows(CommandExecutionException.class, failures::rethrowIfPresent);
        assertTrue(failure.getMessage().contains("discovery did not complete"));
    }

    @Test
    void zeroInitializationBudgetCannotProveAnEmptyTree() {
        ShutdownFailureLedger failures = new ShutdownFailureLedger();
        ShutdownTreeState state = new ShutdownTreeState(new EmptyDescendantProcess(), failures);

        state.initialize(KnownDescendants.empty(), Duration.ZERO);

        assertSame(ShutdownTreeState.DescendantState.UNOBSERVABLE, state.observeDescendants());
    }

    @Test
    void zeroBudgetForceRefreshDoesNotCallProviderAndCannotProveCompletion() {
        CountingDescendantProcess process = new CountingDescendantProcess();
        ShutdownFailureLedger failures = new ShutdownFailureLedger();
        ShutdownTreeState state = new ShutdownTreeState(process, failures);
        state.initialize(KnownDescendants.empty(), Duration.ofSeconds(1));
        assertEquals(1, process.scanCalls());

        state.discoverForForce(Duration.ZERO);

        assertEquals(1, process.scanCalls());
        assertSame(ShutdownTreeState.DescendantState.UNOBSERVABLE, state.observeDescendants());
    }

    @Test
    void completeRefreshRecoversAfterAnEarlierCallerBudgetExpired() {
        EmptyDescendantProcess process = new EmptyDescendantProcess();
        ShutdownTreeState state = new ShutdownTreeState(process, new ShutdownFailureLedger());
        state.initialize(KnownDescendants.empty(), Duration.ofSeconds(1));
        state.discoverForForce(Duration.ZERO);

        state.discoverForForce(Duration.ofSeconds(1));

        assertSame(ShutdownTreeState.DescendantState.EXITED, state.observeDescendants());
        assertTrue(state.hasNoDescendantsAndCompleteDiscovery());
    }

    @Test
    void recoveredDiscoveryRetainsPreviouslyObservedLiveDescendants() {
        MutableProcessHandle known = new MutableProcessHandle(406);
        ShutdownFailureLedger failures = new ShutdownFailureLedger();
        ShutdownTreeState state = new ShutdownTreeState(new EmptyDescendantProcess(), failures);
        state.initialize(knownDescendants(known), Duration.ZERO);

        state.discoverPending(Duration.ofSeconds(1));

        assertEquals(List.of(known), state.takeAllDescendants());
        assertFalse(state.hasNoDescendantsAndCompleteDiscovery());
        assertSame(ShutdownTreeState.DescendantState.LIVE, state.observeDescendants());
        known.destroyForcibly();
        assertSame(ShutdownTreeState.DescendantState.EXITED, state.observeDescendants());
        failures.rethrowIfPresent();
    }

    @Test
    void completeRootScanCannotHideIncompleteKnownDescendantScan() throws Exception {
        CountDownLatch knownScanEntered = new CountDownLatch(1);
        CompletableFuture<Void> releaseKnownScan = new CompletableFuture<>();
        MutableProcessHandle known = new MutableProcessHandle(407) {
            @Override
            public Stream<ProcessHandle> children() {
                knownScanEntered.countDown();
                releaseKnownScan.join();
                return Stream.empty();
            }
        };
        known.destroyForcibly();
        ShutdownFailureLedger failures = new ShutdownFailureLedger();
        ShutdownTreeState state = new ShutdownTreeState(new EmptyDescendantProcess(), failures);
        try {
            state.initialize(knownDescendants(known), Duration.ofMillis(200));

            assertTrue(knownScanEntered.await(1, TimeUnit.SECONDS));
            assertSame(ShutdownTreeState.DescendantState.UNOBSERVABLE, state.observeDescendants());
        } finally {
            releaseKnownScan.complete(null);
        }

        state.discoverPending(Duration.ofSeconds(1));
        assertSame(ShutdownTreeState.DescendantState.EXITED, state.observeDescendants());
        failures.rethrowIfPresent();
    }

    @Test
    void completeKnownDescendantScanCannotHideUnavailableRootDiscovery() {
        AtomicInteger rootScans = new AtomicInteger();
        AtomicInteger knownScans = new AtomicInteger();
        EmptyDescendantProcess process = new EmptyDescendantProcess() {
            @Override
            public Stream<ProcessHandle> descendants() {
                if (rootScans.getAndIncrement() == 0) {
                    throw new SecurityException("root discovery denied");
                }
                return Stream.empty();
            }
        };
        MutableProcessHandle known = new MutableProcessHandle(408) {
            @Override
            public Stream<ProcessHandle> children() {
                knownScans.incrementAndGet();
                return Stream.empty();
            }
        };
        known.destroyForcibly();
        ShutdownFailureLedger failures = new ShutdownFailureLedger();
        ShutdownTreeState state = new ShutdownTreeState(process, failures);
        state.initialize(knownDescendants(known), Duration.ofSeconds(1));

        assertTrue(knownScans.get() > 0);
        assertSame(ShutdownTreeState.DescendantState.UNOBSERVABLE, state.observeDescendants());
        state.discoverPending(Duration.ofSeconds(1));
        assertSame(ShutdownTreeState.DescendantState.UNOBSERVABLE, state.observeDescendants());
        assertThrows(CommandExecutionException.class, failures::rethrowIfPresent);
    }

    @Test
    void unavailableWatcherSnapshotRecordsFailureAndCannotProveCompletion() {
        KnownDescendants unavailable = KnownDescendants.copyOf(new java.util.LinkedHashMap<>(), false, true);
        ShutdownFailureLedger failures = new ShutdownFailureLedger();
        ShutdownTreeState state = new ShutdownTreeState(new EmptyDescendantProcess(), failures);
        state.initialize(unavailable, Duration.ofSeconds(1));

        assertSame(ShutdownTreeState.DescendantState.UNOBSERVABLE, state.observeDescendants());
        CommandExecutionException failure = assertThrows(CommandExecutionException.class, failures::rethrowIfPresent);
        assertTrue(failure.getMessage().contains("discovery did not complete"));
    }

    private static List<MutableProcessHandle> handles(int count, long firstPid) {
        List<MutableProcessHandle> handles = new ArrayList<>(count);
        ProcessHandle.Info processInfo = ProcessHandle.current().info();
        for (int index = 0; index < count; index++) {
            handles.add(new MutableProcessHandle(firstPid + index) {
                @Override
                public Info info() {
                    return processInfo;
                }
            });
        }
        return handles;
    }

    private abstract static class TestProcess extends Process {

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
        public int exitValue() {
            return 0;
        }

        @Override
        public void destroy() {}
    }

    private static class EmptyDescendantProcess extends TestProcess {

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }

    private static final class PrefixFailingDescendantProcess extends TestProcess {

        private final ProcessHandle observed;
        private final AssertionError failure;

        private PrefixFailingDescendantProcess(ProcessHandle observed, AssertionError failure) {
            this.observed = observed;
            this.failure = failure;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.concat(Stream.of(observed), Stream.generate(() -> {
                throw failure;
            }));
        }
    }

    private static final class CountingDescendantProcess extends EmptyDescendantProcess {

        private final AtomicInteger scanCalls = new AtomicInteger();

        @Override
        public Stream<ProcessHandle> descendants() {
            scanCalls.incrementAndGet();
            return Stream.empty();
        }

        int scanCalls() {
            return scanCalls.get();
        }
    }

    private static final class SequencedDescendantProcess extends EmptyDescendantProcess {

        private final AtomicInteger scans = new AtomicInteger();
        private final ProcessHandle[] descendants;

        private SequencedDescendantProcess(ProcessHandle... descendants) {
            this.descendants = descendants;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return scans.getAndIncrement() == 0 ? Stream.empty() : Stream.of(descendants);
        }
    }

    private static final class FailingDescendantProcess extends TestProcess {

        private final Throwable failure;

        private FailingDescendantProcess(Throwable failure) {
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
