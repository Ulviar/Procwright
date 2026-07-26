/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.MutableProcessHandle;
import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.knownDescendants;
import static io.github.ulviar.procwright.internal.ThrowableMonitorTestSupport.hold;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class LiveDescendantSnapshotTest {

    @Test
    void refreshAccumulatesLiveHandlesPrunesExitedOnesAndRetainsUnobservableOnes() throws Exception {
        TestHandle exited = TestHandle.live(101);
        exited.alive.set(false);
        TestHandle unobservable = TestHandle.unobservable(102);
        TestHandle firstLive = TestHandle.live(103);
        TestHandle secondLive = TestHandle.live(104);
        LiveDescendantSnapshot snapshot = new LiveDescendantSnapshot(knownDescendants(exited, unobservable));

        snapshot.refresh(
                new DescendantProcess(firstLive),
                Duration.ofSeconds(1),
                DurationSupport.deadlineFromNow(Duration.ofSeconds(1)));
        snapshot.refresh(
                new DescendantProcess(secondLive),
                Duration.ofSeconds(1),
                DurationSupport.deadlineFromNow(Duration.ofSeconds(1)));

        assertFalse(snapshot.current().contains(exited));
        assertTrue(snapshot.current().contains(unobservable));
        assertTrue(snapshot.current().contains(firstLive));
        assertTrue(snapshot.current().contains(secondLive));
        assertThrows(
                UnsupportedOperationException.class, () -> snapshot.current().clear());
    }

    @Test
    void initialSnapshotIsBoundedAndRetainsInsertionOrder() {
        int limit = ProcessTreeScanner.shared().descendantLimit();
        List<TestHandle> handles = handles(limit + 1, 1_000);

        LiveDescendantSnapshot snapshot = new LiveDescendantSnapshot(knownDescendants(handles));
        List<ProcessHandle> retained = List.copyOf(snapshot.current());

        assertEquals(limit, retained.size());
        assertSame(handles.get(0), retained.get(0));
        assertSame(handles.get(limit - 1), retained.get(retained.size() - 1));
        assertFalse(snapshot.current().contains(handles.get(limit)));
        assertTrue(snapshot.sealForCleanup().truncated());
    }

    @Test
    void refreshBoundsTheMergedSnapshotAndKeepsPreviouslyObservedHandlesFirst() throws Exception {
        int limit = ProcessTreeScanner.shared().descendantLimit();
        List<TestHandle> initial = handles(limit - 1, 10_000);
        TestHandle firstNew = TestHandle.live(20_000);
        TestHandle overflow = TestHandle.live(20_001);
        LiveDescendantSnapshot snapshot = new LiveDescendantSnapshot(knownDescendants(initial));

        snapshot.refresh(
                new DescendantProcess(firstNew, overflow),
                Duration.ofSeconds(1),
                DurationSupport.deadlineFromNow(Duration.ofSeconds(1)));

        List<ProcessHandle> retained = List.copyOf(snapshot.current());
        assertEquals(limit, retained.size());
        assertSame(initial.get(0), retained.get(0));
        assertSame(firstNew, retained.get(retained.size() - 1));
        assertFalse(snapshot.current().contains(overflow));
        assertTrue(snapshot.sealForCleanup().truncated());
    }

    @Test
    void guardedUnknownIsRetainedWithoutInvokingTheProvider() throws Exception {
        TestHandle delegate = TestHandle.live(301);
        ProcessHandle guarded = new ProcessTreeScanner(1, 4, Duration.ofMillis(25)).guardObserved(delegate);
        LiveDescendantSnapshot snapshot = new LiveDescendantSnapshot(knownDescendants(guarded));

        snapshot.refresh(new DescendantProcess(), Duration.ZERO, System.nanoTime() - 1);

        assertTrue(snapshot.current().contains(guarded));
        assertEquals(0, delegate.livenessCalls.get());
    }

    @Test
    void guardedExitedHandleIsPruned() throws Exception {
        TestHandle delegate = TestHandle.live(302);
        delegate.alive.set(false);
        ProcessHandle guarded = new ProcessTreeScanner(1, 4, Duration.ofMillis(25)).guardObserved(delegate);
        LiveDescendantSnapshot snapshot = new LiveDescendantSnapshot(knownDescendants(guarded));

        snapshot.refresh(
                new DescendantProcess(), Duration.ZERO, DurationSupport.deadlineFromNow(Duration.ofSeconds(1)));

        assertTrue(snapshot.current().isEmpty());
        assertEquals(1, delegate.livenessCalls.get());
    }

    @Test
    void guardedProviderFailureLeavesThePreviousSnapshotUnchanged() {
        IllegalStateException expected = new IllegalStateException("provider failed");
        TestHandle stable = TestHandle.live(303);
        ProcessHandle failing =
                new ProcessTreeScanner(1, 4, Duration.ofMillis(25)).guardObserved(TestHandle.failing(304, expected));
        LiveDescendantSnapshot snapshot = new LiveDescendantSnapshot(knownDescendants(stable, failing));
        Set<ProcessHandle> before = snapshot.current();

        IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> snapshot.refresh(
                        new DescendantProcess(),
                        Duration.ZERO,
                        DurationSupport.deadlineFromNow(Duration.ofSeconds(1))));

        assertSame(expected, actual);
        assertEquals(before, snapshot.current());
    }

    @Test
    void pruningFailureStillPublishesTheNewlyScannedPrefix() {
        IllegalStateException expected = new IllegalStateException("provider failed");
        TestHandle stable = TestHandle.live(307);
        ProcessHandle failing =
                new ProcessTreeScanner(1, 4, Duration.ofMillis(25)).guardObserved(TestHandle.failing(308, expected));
        TestHandle newlyObserved = TestHandle.live(309);
        LiveDescendantSnapshot snapshot = new LiveDescendantSnapshot(knownDescendants(stable, failing));

        IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> snapshot.refresh(
                        new DescendantProcess(newlyObserved),
                        Duration.ofSeconds(1),
                        DurationSupport.deadlineFromNow(Duration.ofSeconds(1))));

        assertSame(expected, actual);
        assertTrue(snapshot.current().contains(stable));
        assertTrue(snapshot.current().contains(failing));
        assertTrue(snapshot.current().contains(newlyObserved));
    }

    @Test
    void freshLivenessBudgetStartsAfterTheBoundedDescendantScan() throws Exception {
        TestHandle descendant = TestHandle.live(105);
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(25));
        ProcessHandle guardedDescendant = scanner.guardObserved(descendant);
        LiveDescendantSnapshot snapshot = new LiveDescendantSnapshot(knownDescendants(guardedDescendant));
        BlockingDescendantScanProcess process = new BlockingDescendantScanProcess();

        try {
            snapshot.refreshWithFreshLivenessBudget(process, Duration.ofMillis(25));
        } finally {
            process.releaseScan.countDown();
        }

        assertEquals(1, descendant.livenessCalls.get());
        assertTrue(snapshot.current().contains(guardedDescendant));
    }

    @Test
    void completeRefreshDoesNotEraseEarlierUnavailableObservation() throws Exception {
        LiveDescendantSnapshot snapshot = new LiveDescendantSnapshot();
        snapshot.refreshWithFreshLivenessBudget(new UnavailableDescendantProcess(), Duration.ofMillis(25));

        snapshot.refreshWithFreshLivenessBudget(new DescendantProcess(), Duration.ofSeconds(1));

        assertTrue(snapshot.sealForCleanup().discoveryUnavailable());
    }

    @Test
    void observationDeadlineDoesNotPoisonLaterCleanup() throws Exception {
        LiveDescendantSnapshot snapshot = new LiveDescendantSnapshot();
        BlockingDescendantScanProcess blocking = new BlockingDescendantScanProcess();
        try {
            snapshot.refreshWithFreshLivenessBudget(blocking, Duration.ofMillis(25));
        } finally {
            blocking.releaseScan.countDown();
        }

        assertFalse(snapshot.sealForCleanup().discoveryUnavailable());
    }

    @Test
    void cleanupSnapshotWaitsForAnActiveRefreshHandoff() throws Exception {
        LiveDescendantSnapshot snapshot = new LiveDescendantSnapshot();
        BlockingUnavailableDescendantProcess process = new BlockingUnavailableDescendantProcess();
        Thread watcher = new Thread(() -> {
            try {
                snapshot.refreshWithFreshLivenessBudget(process, Duration.ofSeconds(1));
            } catch (InterruptedException failure) {
                throw new AssertionError(failure);
            }
        });
        CountDownLatch readerStarted = new CountDownLatch(1);
        AtomicReference<KnownDescendants> handedOff = new AtomicReference<>();
        Thread cleanup = new Thread(() -> {
            readerStarted.countDown();
            handedOff.set(snapshot.sealForCleanup());
        });

        watcher.start();
        try {
            assertTrue(process.scanEntered.await(1, TimeUnit.SECONDS));
            cleanup.start();
            assertTrue(readerStarted.await(1, TimeUnit.SECONDS));
            cleanup.join(100);
            assertTrue(cleanup.isAlive(), "cleanup snapshot must not overtake an active refresh");
        } finally {
            process.releaseScan.countDown();
            watcher.join(TimeUnit.SECONDS.toMillis(1));
            cleanup.join(TimeUnit.SECONDS.toMillis(1));
        }

        assertFalse(watcher.isAlive());
        assertFalse(cleanup.isAlive());
        assertTrue(handedOff.get().discoveryUnavailable());
    }

    @Test
    void interruptedRefreshIsNonStickyAndPreservesCallerInterrupt() throws Exception {
        AssertionError forbiddenLiveness = new AssertionError("retained handle must not be pruned after interruption");
        LiveDescendantSnapshot snapshot =
                new LiveDescendantSnapshot(knownDescendants(TestHandle.failing(305, forbiddenLiveness)));
        AtomicBoolean interrupted = new AtomicBoolean();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread caller = new Thread(() -> {
            try {
                snapshotRefresh(snapshot, new InterruptingDescendantScanProcess(Thread.currentThread()));
                interrupted.set(Thread.currentThread().isInterrupted());
            } catch (Throwable observed) {
                failure.set(observed);
            }
        });

        caller.start();
        caller.join(TimeUnit.SECONDS.toMillis(1));

        assertFalse(caller.isAlive());
        assertSame(null, failure.get());
        assertTrue(interrupted.get());
        assertEquals(0, ((TestHandle) snapshot.current().iterator().next()).livenessCalls.get());
        assertFalse(snapshot.sealForCleanup().discoveryUnavailable());
    }

    @Test
    void pruningInterruptionRemainsPrimaryWhenScanAlsoCarriesFatalFailure() throws Exception {
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofSeconds(1));
        BlockingLivenessHandle retainedDelegate = new BlockingLivenessHandle(310);
        ProcessHandle retained = scanner.guardObserved(retainedDelegate);
        TestHandle scannedPrefix = TestHandle.live(311);
        AssertionError scanFailure = new AssertionError("fatal scan after prefix");
        LiveDescendantSnapshot snapshot = new LiveDescendantSnapshot(knownDescendants(retained));
        AtomicReference<Throwable> observed = new AtomicReference<>();
        CountDownLatch reported = new CountDownLatch(1);
        AtomicReference<Throwable> reportedFailure = new AtomicReference<>();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            reportedFailure.set(failure);
            reported.countDown();
        });
        Thread caller = new Thread(() -> {
            try {
                snapshot.refresh(
                        new FatalPrefixDescendantProcess(scannedPrefix, scanFailure),
                        Duration.ofSeconds(1),
                        DurationSupport.deadlineFromNow(Duration.ofSeconds(1)));
            } catch (Throwable failure) {
                observed.set(failure);
            }
        });

        try {
            caller.start();
            assertTrue(retainedDelegate.livenessEntered.await(1, TimeUnit.SECONDS));
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(1));

            assertFalse(caller.isAlive());
            assertTrue(observed.get() instanceof InterruptedException);
            assertEquals(0, observed.get().getSuppressed().length);
            assertTrue(reported.await(1, TimeUnit.SECONDS));
            assertSame(scanFailure, reportedFailure.get());
            assertTrue(snapshot.current().contains(retained));
            assertTrue(snapshot.current().contains(scannedPrefix));
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Test
    void failureAggregationDoesNotDelayRefreshOrCleanupHandoff() throws Exception {
        AssertionError scanFailure = new AssertionError("fatal scan after prefix");
        IllegalStateException pruningFailure = new IllegalStateException("liveness failed");
        TestHandle scannedPrefix = TestHandle.live(312);
        LiveDescendantSnapshot snapshot =
                new LiveDescendantSnapshot(knownDescendants(TestHandle.failing(313, pruningFailure)));
        AtomicReference<Throwable> observed = new AtomicReference<>();
        AtomicReference<KnownDescendants> handedOff = new AtomicReference<>();
        Thread refresh = new Thread(() -> {
            try {
                snapshot.refresh(
                        new FatalPrefixDescendantProcess(scannedPrefix, scanFailure),
                        Duration.ofSeconds(1),
                        DurationSupport.deadlineFromNow(Duration.ofSeconds(1)));
            } catch (Throwable failure) {
                observed.set(failure);
            }
        });
        Thread cleanup = new Thread(() -> handedOff.set(snapshot.sealForCleanup()));

        try (var monitor = hold(scanFailure)) {
            monitor.verifyHeld();
            refresh.start();
            refresh.join(TimeUnit.SECONDS.toMillis(1));
            assertFalse(refresh.isAlive());

            cleanup.start();
            cleanup.join(TimeUnit.SECONDS.toMillis(1));

            assertFalse(cleanup.isAlive(), "diagnostic attachment must not retain snapshot ownership");
            assertTrue(handedOff.get().handles().contains(scannedPrefix));
        } finally {
            refresh.join(TimeUnit.SECONDS.toMillis(1));
            cleanup.join(TimeUnit.SECONDS.toMillis(1));
        }

        assertFalse(refresh.isAlive());
        assertFalse(cleanup.isAlive());
        assertSame(scanFailure, observed.get().getCause());
        assertEquals(List.of(pruningFailure), List.of(observed.get().getSuppressed()));
        assertEquals(0, scanFailure.getSuppressed().length);
    }

    @Test
    void refreshCannotPublishAfterCleanupHandoff() throws Exception {
        LiveDescendantSnapshot snapshot = new LiveDescendantSnapshot();
        TestHandle late = TestHandle.live(306);

        KnownDescendants handedOff = snapshot.sealForCleanup();
        snapshot.refreshWithFreshLivenessBudget(new DescendantProcess(late), Duration.ofSeconds(1));

        assertTrue(handedOff.handles().isEmpty());
        assertTrue(snapshot.current().isEmpty());
    }

    private static void snapshotRefresh(LiveDescendantSnapshot snapshot, Process process) {
        try {
            snapshot.refreshWithFreshLivenessBudget(process, Duration.ofSeconds(1));
        } catch (InterruptedException failure) {
            throw new AssertionError(failure);
        }
    }

    private static List<TestHandle> handles(int count, long firstPid) {
        List<TestHandle> handles = new ArrayList<>(count);
        for (int index = 0; index < count; index++) {
            handles.add(TestHandle.live(firstPid + index));
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

    private static final class DescendantProcess extends TestProcess {

        private final ProcessHandle[] descendants;

        private DescendantProcess(ProcessHandle... descendants) {
            this.descendants = descendants;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.of(descendants);
        }
    }

    private static final class UnavailableDescendantProcess extends TestProcess {

        @Override
        public Stream<ProcessHandle> descendants() {
            throw new IllegalStateException("process tree unavailable");
        }
    }

    private static final class BlockingDescendantScanProcess extends TestProcess {

        private final CountDownLatch releaseScan = new CountDownLatch(1);

        @Override
        public Stream<ProcessHandle> descendants() {
            boolean restoreInterrupt = false;
            while (true) {
                try {
                    if (releaseScan.await(1, TimeUnit.SECONDS)) {
                        break;
                    }
                } catch (InterruptedException interruption) {
                    restoreInterrupt = true;
                }
            }
            if (restoreInterrupt) {
                Thread.currentThread().interrupt();
            }
            return Stream.empty();
        }
    }

    private static final class BlockingUnavailableDescendantProcess extends TestProcess {

        private final CountDownLatch scanEntered = new CountDownLatch(1);
        private final CountDownLatch releaseScan = new CountDownLatch(1);

        @Override
        public Stream<ProcessHandle> descendants() {
            scanEntered.countDown();
            try {
                releaseScan.await();
            } catch (InterruptedException interruption) {
                Thread.currentThread().interrupt();
            }
            throw new IllegalStateException("process tree unavailable");
        }
    }

    private static final class InterruptingDescendantScanProcess extends TestProcess {

        private final Thread caller;

        private InterruptingDescendantScanProcess(Thread caller) {
            this.caller = caller;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            caller.interrupt();
            while (!Thread.currentThread().isInterrupted()) {
                Thread.onSpinWait();
            }
            return Stream.empty();
        }
    }

    private static final class FatalPrefixDescendantProcess extends TestProcess {

        private final ProcessHandle prefix;
        private final AssertionError failure;

        private FatalPrefixDescendantProcess(ProcessHandle prefix, AssertionError failure) {
            this.prefix = prefix;
            this.failure = failure;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.concat(Stream.of(prefix), Stream.generate(() -> {
                throw failure;
            }));
        }
    }

    private static final class BlockingLivenessHandle extends MutableProcessHandle {

        private final CountDownLatch livenessEntered = new CountDownLatch(1);

        private BlockingLivenessHandle(long pid) {
            super(pid);
        }

        @Override
        public boolean isAlive() {
            livenessEntered.countDown();
            try {
                new CountDownLatch(1).await();
            } catch (InterruptedException expectedCancellation) {
                Thread.currentThread().interrupt();
            }
            return true;
        }
    }

    private static final class TestHandle implements ProcessHandle {

        private final long pid;
        private final Throwable livenessFailure;
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger livenessCalls = new AtomicInteger();

        private TestHandle(long pid, Throwable livenessFailure) {
            this.pid = pid;
            this.livenessFailure = livenessFailure;
        }

        static TestHandle live(long pid) {
            return new TestHandle(pid, null);
        }

        static TestHandle unobservable(long pid) {
            return new TestHandle(pid, new SecurityException("liveness unavailable"));
        }

        static TestHandle failing(long pid, Throwable failure) {
            return new TestHandle(pid, failure);
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
            alive.set(false);
            return true;
        }

        @Override
        public boolean destroyForcibly() {
            alive.set(false);
            return true;
        }

        @Override
        public boolean isAlive() {
            livenessCalls.incrementAndGet();
            if (livenessFailure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (livenessFailure instanceof Error error) {
                throw error;
            }
            return alive.get();
        }

        @Override
        public int compareTo(ProcessHandle other) {
            return Long.compare(pid, other.pid());
        }
    }
}
