/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
        LiveDescendantSnapshot snapshot =
                new LiveDescendantSnapshot(new LinkedHashSet<>(List.of(exited, unobservable)));

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

        LiveDescendantSnapshot snapshot = new LiveDescendantSnapshot(new LinkedHashSet<>(handles));
        List<ProcessHandle> retained = List.copyOf(snapshot.current());

        assertEquals(limit, retained.size());
        assertSame(handles.get(0), retained.get(0));
        assertSame(handles.get(limit - 1), retained.get(retained.size() - 1));
        assertFalse(snapshot.current().contains(handles.get(limit)));
    }

    @Test
    void refreshBoundsTheMergedSnapshotAndKeepsPreviouslyObservedHandlesFirst() throws Exception {
        int limit = ProcessTreeScanner.shared().descendantLimit();
        List<TestHandle> initial = handles(limit - 1, 10_000);
        TestHandle firstNew = TestHandle.live(20_000);
        TestHandle overflow = TestHandle.live(20_001);
        LiveDescendantSnapshot snapshot = new LiveDescendantSnapshot(new LinkedHashSet<>(initial));

        snapshot.refresh(
                new DescendantProcess(firstNew, overflow),
                Duration.ofSeconds(1),
                DurationSupport.deadlineFromNow(Duration.ofSeconds(1)));

        List<ProcessHandle> retained = List.copyOf(snapshot.current());
        assertEquals(limit, retained.size());
        assertSame(initial.get(0), retained.get(0));
        assertSame(firstNew, retained.get(retained.size() - 1));
        assertFalse(snapshot.current().contains(overflow));
    }

    @Test
    void guardedUnknownIsRetainedWithoutInvokingTheProvider() throws Exception {
        TestHandle delegate = TestHandle.live(301);
        ProcessHandle guarded = new ProcessTreeScanner(1, 4, Duration.ofMillis(25)).guardObserved(delegate);
        LiveDescendantSnapshot snapshot = new LiveDescendantSnapshot(Set.of(guarded));

        snapshot.refresh(new DescendantProcess(), Duration.ZERO, System.nanoTime() - 1);

        assertTrue(snapshot.current().contains(guarded));
        assertEquals(0, delegate.livenessCalls.get());
    }

    @Test
    void guardedExitedHandleIsPruned() throws Exception {
        TestHandle delegate = TestHandle.live(302);
        delegate.alive.set(false);
        ProcessHandle guarded = new ProcessTreeScanner(1, 4, Duration.ofMillis(25)).guardObserved(delegate);
        LiveDescendantSnapshot snapshot = new LiveDescendantSnapshot(Set.of(guarded));

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
        LiveDescendantSnapshot snapshot = new LiveDescendantSnapshot(new LinkedHashSet<>(List.of(stable, failing)));
        Set<ProcessHandle> before = snapshot.current();

        IllegalStateException actual = assertThrows(
                IllegalStateException.class,
                () -> snapshot.refresh(
                        new DescendantProcess(),
                        Duration.ZERO,
                        DurationSupport.deadlineFromNow(Duration.ofSeconds(1))));

        assertSame(expected, actual);
        assertSame(before, snapshot.current());
    }

    @Test
    void freshLivenessBudgetStartsAfterTheBoundedDescendantScan() throws Exception {
        TestHandle descendant = TestHandle.live(105);
        ProcessTreeScanner scanner = new ProcessTreeScanner(1, 4, Duration.ofMillis(25));
        ProcessHandle guardedDescendant = scanner.guardObserved(descendant);
        LiveDescendantSnapshot snapshot = new LiveDescendantSnapshot(Set.of(guardedDescendant));
        BlockingDescendantScanProcess process = new BlockingDescendantScanProcess();

        try {
            snapshot.refreshWithFreshLivenessBudget(process, Duration.ofMillis(25));
        } finally {
            process.releaseScan.countDown();
        }

        assertEquals(1, descendant.livenessCalls.get());
        assertTrue(snapshot.current().contains(guardedDescendant));
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
