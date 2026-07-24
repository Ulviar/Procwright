/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class OneShotTerminationTest extends ProcessKernelProcessFixtureSupport {

    @Test
    void completedStdinFailureWinsBeforeProcessExitOrTimeout() throws Exception {
        IllegalStateException expected = new IllegalStateException("stdin failed");
        OneShotTermination termination = termination(liveProcess(), Duration.ofSeconds(1));

        termination.stdinFailureHandler().accept(expected);

        OneShotTermination.StdinFailure outcome =
                assertInstanceOf(OneShotTermination.StdinFailure.class, termination.await());
        assertSame(expected, outcome.failure());
    }

    @Test
    void processExitRemainsWinnerWhenStdinFailsLater() throws Exception {
        OneShotTermination termination = termination(exitedProcess(), Duration.ofSeconds(1));

        assertInstanceOf(OneShotTermination.ProcessExited.class, termination.await());

        termination.stdinFailureHandler().accept(new IllegalStateException("late failure"));
        assertInstanceOf(OneShotTermination.ProcessExited.class, termination.await());
    }

    @Test
    void timeoutWinsWhenNoEarlierTerminalEventOccurs() throws Exception {
        OneShotTermination termination = termination(liveProcess(), Duration.ofMillis(1));

        assertInstanceOf(OneShotTermination.TimedOut.class, termination.await());
    }

    @Test
    void selectedOutcomeWinsConcurrentInterruptionAndInterruptStatusIsRestored() throws Exception {
        AtomicReference<Runnable> beforeInterruption = new AtomicReference<>();
        InterruptingWaitProcess process = new InterruptingWaitProcess(beforeInterruption);
        OneShotTermination termination = termination(process, Duration.ofSeconds(1));
        IllegalStateException expected = new IllegalStateException("stdin failed");
        beforeInterruption.set(() -> termination.stdinFailureHandler().accept(expected));

        try {
            OneShotTermination.StdinFailure outcome =
                    assertInstanceOf(OneShotTermination.StdinFailure.class, termination.await());

            assertSame(expected, outcome.failure());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptionPropagatesWhenNoTerminalOutcomeWasSelected() {
        AtomicReference<Runnable> beforeInterruption = new AtomicReference<>(() -> {});
        InterruptingWaitProcess process = new InterruptingWaitProcess(beforeInterruption);
        OneShotTermination termination = termination(process, Duration.ofSeconds(1));

        InterruptedException actual = assertThrows(InterruptedException.class, termination::await);

        assertSame(process.interruption, actual);
    }

    private static OneShotTermination termination(Process process, Duration timeout) {
        return new OneShotTermination(process, timeout, new AtomicReference<>(Set.of()));
    }

    private static TerminalProcess exitedProcess() {
        return new TerminalProcess(new TrackingInputStream(), new TrackingInputStream(), new TrackingOutputStream());
    }

    private static TerminalProcess liveProcess() {
        return new TerminalProcess(
                new TrackingInputStream(), new TrackingInputStream(), new TrackingOutputStream(), true);
    }

    private static final class InterruptingWaitProcess extends Process {

        private final AtomicReference<Runnable> beforeInterruption;
        private final InterruptedException interruption = new InterruptedException("test interruption");

        private InterruptingWaitProcess(AtomicReference<Runnable> beforeInterruption) {
            this.beforeInterruption = beforeInterruption;
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
        public int waitFor() throws InterruptedException {
            throw interruption;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            beforeInterruption.get().run();
            throw interruption;
        }

        @Override
        public int exitValue() {
            throw new IllegalThreadStateException("alive");
        }

        @Override
        public void destroy() {}

        @Override
        public Process destroyForcibly() {
            return this;
        }

        @Override
        public boolean isAlive() {
            return true;
        }

        @Override
        public ProcessHandle toHandle() {
            throw new UnsupportedOperationException("test process has no handle");
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }
}
