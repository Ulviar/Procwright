/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class ProcessExitWaiterTest extends ProcessLifecycleSharedSupport {

    @Test
    void zeroTimeoutWaitsWithoutADeadline() throws Exception {
        WaitCompletingProcess process = new WaitCompletingProcess();

        assertTrue(ProcessExitWaiter.waitFor(process, Duration.ZERO, new LiveDescendantSnapshot()));

        assertEquals(1, process.timedWaitCalls.get());
    }

    @Test
    void negativeTimeoutIsRejectedBeforeProcessObservation() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ProcessExitWaiter.waitFor(
                        new ObservationHostileProcess(), Duration.ofNanos(-1), new LiveDescendantSnapshot()));
    }

    @Test
    void ordinaryProcessGetsAFinalProbeWhenDescendantScanConsumesTheDeadline() throws Exception {
        AdvancingClock clock = new AdvancingClock();
        ExitDuringScanProcess process = new ExitDuringScanProcess(clock);

        assertTrue(ProcessExitWaiter.waitFor(process, Duration.ofMillis(250), new LiveDescendantSnapshot(), clock));
    }

    @Test
    void interruptedDescendantScanWinsOverAnExpiredWaitDeadline() {
        AdvancingClock clock = new AdvancingClock();
        Thread caller = Thread.currentThread();
        InterruptingScanProcess process = new InterruptingScanProcess(caller, clock);

        try {
            assertThrows(
                    InterruptedException.class,
                    () -> ProcessExitWaiter.waitFor(
                            process, Duration.ofMillis(250), new LiveDescendantSnapshot(), clock));
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void expiredDeadlineStillRecognizesAnAlreadyExitedProcess() throws Exception {
        LiveDescendantSnapshot descendants = new LiveDescendantSnapshot();

        assertTrue(ProcessLifecycle.waitFor(new CompletedProcess(), Duration.ofNanos(1), descendants));
    }

    @Test
    void descendantSnapshotAccumulatesHandlesAcrossPolls() throws Exception {
        LiveDescendantSnapshot descendants = new LiveDescendantSnapshot();
        ProcessHandle observedBeforeReparenting = ProcessHandle.current();

        assertTrue(ProcessLifecycle.waitFor(
                new ReparentingProcess(observedBeforeReparenting), Duration.ofSeconds(1), descendants));

        assertTrue(descendants.current().contains(observedBeforeReparenting));
    }

    @Test
    void descendantSnapshotPrunesExitedHandles() throws Exception {
        ProcessHandle exited = new TestProcessHandle(42, false);
        ProcessHandle live = ProcessHandle.current();
        LiveDescendantSnapshot descendants = new LiveDescendantSnapshot(knownDescendants(exited));

        assertTrue(ProcessLifecycle.waitFor(new ReparentingProcess(live), Duration.ofSeconds(1), descendants));

        assertFalse(descendants.current().contains(exited));
        assertTrue(descendants.current().contains(live));
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
            if (isAlive()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 0;
        }

        @Override
        public void destroy() {}

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }

    private static final class WaitCompletingProcess extends TestProcess {

        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicInteger timedWaitCalls = new AtomicInteger();

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            timedWaitCalls.incrementAndGet();
            alive.set(false);
            return true;
        }
    }

    private static final class ObservationHostileProcess extends TestProcess {

        @Override
        public boolean isAlive() {
            throw new AssertionError("must not observe process");
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            throw new AssertionError("must not scan descendants");
        }
    }

    private static final class ExitDuringScanProcess extends TestProcess {

        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AdvancingClock clock;

        private ExitDuringScanProcess(AdvancingClock clock) {
            this.clock = clock;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            alive.set(false);
            clock.nanos = Duration.ofMillis(250).toNanos();
            return Stream.empty();
        }
    }

    private static final class InterruptingScanProcess extends TestProcess {

        private final Thread caller;
        private final AdvancingClock clock;

        private InterruptingScanProcess(Thread caller, AdvancingClock clock) {
            this.caller = caller;
            this.clock = clock;
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            clock.nanos = Duration.ofMillis(250).toNanos();
            caller.interrupt();
            while (!Thread.currentThread().isInterrupted()) {
                Thread.onSpinWait();
            }
            return Stream.empty();
        }
    }

    private static final class ReparentingProcess extends Process {

        private final ProcessHandle initiallyVisibleDescendant;
        private int polls;
        private boolean alive = true;

        private ReparentingProcess(ProcessHandle initiallyVisibleDescendant) {
            this.initiallyVisibleDescendant = initiallyVisibleDescendant;
        }

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
            alive = false;
            return 0;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            polls++;
            if (polls >= 2) {
                alive = false;
            }
            return !alive;
        }

        @Override
        public int exitValue() {
            if (alive) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 0;
        }

        @Override
        public boolean isAlive() {
            return alive;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return polls == 0 ? Stream.of(initiallyVisibleDescendant) : Stream.empty();
        }

        @Override
        public void destroy() {
            alive = false;
        }
    }

    private record TestProcessHandle(long pid, boolean alive) implements ProcessHandle {

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
            return alive;
        }

        @Override
        public int compareTo(ProcessHandle other) {
            return Long.compare(pid, other.pid());
        }
    }

    private static final class AdvancingClock implements ProcessExitWaiter.PollClock {

        private volatile long nanos;

        @Override
        public long nanoTime() {
            return nanos;
        }

        @Override
        public void sleep(long durationNanos) {
            nanos += durationNanos;
        }
    }
}
