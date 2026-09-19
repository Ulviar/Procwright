/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.AdvancingWaitClock;
import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.CompletedProcess;
import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.MutableProcessHandle;
import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.WaiterProcess;
import static io.github.ulviar.procwright.internal.ProcessLifecycleTestFixtures.knownDescendants;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class ProcessExitWaiterTest {

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
        AdvancingWaitClock clock = new AdvancingWaitClock();
        ExitDuringScanProcess process = new ExitDuringScanProcess(clock);

        assertTrue(ProcessExitWaiter.waitFor(
                process, Duration.ofMillis(250), new LiveDescendantSnapshot(), clock::nanoTime));
    }

    @Test
    void interruptedDescendantScanWinsOverAnExpiredWaitDeadline() {
        AdvancingWaitClock clock = new AdvancingWaitClock();
        Thread caller = Thread.currentThread();
        InterruptingScanProcess process = new InterruptingScanProcess(caller, clock);

        try {
            assertThrows(
                    InterruptedException.class,
                    () -> ProcessExitWaiter.waitFor(
                            process, Duration.ofMillis(250), new LiveDescendantSnapshot(), clock::nanoTime));
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
        MutableProcessHandle exited = new MutableProcessHandle(42);
        exited.destroyForcibly();
        ProcessHandle live = ProcessHandle.current();
        LiveDescendantSnapshot descendants = new LiveDescendantSnapshot(knownDescendants(exited));

        assertTrue(ProcessLifecycle.waitFor(new ReparentingProcess(live), Duration.ofSeconds(1), descendants));

        assertFalse(descendants.current().contains(exited));
        assertTrue(descendants.current().contains(live));
    }

    private static final class WaitCompletingProcess extends WaiterProcess {

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

    private static final class ObservationHostileProcess extends WaiterProcess {

        @Override
        public boolean isAlive() {
            throw new AssertionError("must not observe process");
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            throw new AssertionError("must not scan descendants");
        }
    }

    private static final class ExitDuringScanProcess extends WaiterProcess {

        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AdvancingWaitClock clock;

        private ExitDuringScanProcess(AdvancingWaitClock clock) {
            this.clock = clock;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            alive.set(false);
            clock.advanceTo(Duration.ofMillis(250));
            return Stream.empty();
        }
    }

    private static final class InterruptingScanProcess extends WaiterProcess {

        private final Thread caller;
        private final AdvancingWaitClock clock;

        private InterruptingScanProcess(Thread caller, AdvancingWaitClock clock) {
            this.caller = caller;
            this.clock = clock;
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            clock.advanceTo(Duration.ofMillis(250));
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
}
