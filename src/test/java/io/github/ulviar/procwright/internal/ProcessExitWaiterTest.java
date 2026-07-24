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
        AdvancingClock clock = new AdvancingClock();
        ExitDuringScanProcess process = new ExitDuringScanProcess(clock);

        assertTrue(ProcessExitWaiter.waitFor(process, Duration.ofMillis(250), new LiveDescendantSnapshot(), clock));
    }

    @Test
    void guardedProcessDoesNotStartANewProviderProbeAfterTheDeadline() throws Exception {
        AdvancingClock clock = new AdvancingClock();
        GuardedDeadlineProcess delegate = new GuardedDeadlineProcess(clock);
        Process guarded = new ProcessTreeScanner(2, 4, Duration.ofMillis(25)).guard(delegate);

        assertFalse(ProcessExitWaiter.waitFor(guarded, Duration.ofMillis(250), new LiveDescendantSnapshot(), clock));

        assertEquals(1, delegate.livenessCalls.get());
    }

    @Test
    void guardedWaitPropagatesTheOriginalSleepInterruption() {
        InterruptedException expected = new InterruptedException("stop waiting");
        Process guarded = new ProcessTreeScanner(1, 4, Duration.ofMillis(25)).guard(new AlwaysLiveProcess());
        ProcessExitWaiter.PollClock clock = new ProcessExitWaiter.PollClock() {
            @Override
            public long nanoTime() {
                return 0;
            }

            @Override
            public void sleep(long nanos) throws InterruptedException {
                throw expected;
            }
        };

        InterruptedException actual = assertThrows(
                InterruptedException.class,
                () -> ProcessExitWaiter.waitFor(guarded, Duration.ofSeconds(1), new LiveDescendantSnapshot(), clock));

        assertSame(expected, actual);
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

    private static final class AlwaysLiveProcess extends TestProcess {

        @Override
        public boolean isAlive() {
            return true;
        }
    }

    private static final class GuardedDeadlineProcess extends TestProcess {

        private final AdvancingClock clock;
        private final AtomicInteger livenessCalls = new AtomicInteger();

        private GuardedDeadlineProcess(AdvancingClock clock) {
            this.clock = clock;
        }

        @Override
        public boolean isAlive() {
            livenessCalls.incrementAndGet();
            return true;
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            clock.nanos = Duration.ofMillis(250).toNanos();
            return Stream.empty();
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
