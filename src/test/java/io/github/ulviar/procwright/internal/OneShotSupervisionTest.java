/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class OneShotSupervisionTest extends ProcessKernelProcessFixtureSupport {

    @Test
    void completedStdinFailureWinsBeforeProcessExitOrTimeout() throws Exception {
        IllegalStateException expected = new IllegalStateException("stdin failed");
        OneShotSupervision supervision = supervision(liveProcess(), Duration.ofSeconds(1));

        supervision.stdinFailureHandler().accept(expected);

        OneShotSupervision.StdinFailure signal =
                assertInstanceOf(OneShotSupervision.StdinFailure.class, supervision.await());
        assertSame(expected, signal.failure());
    }

    @Test
    void processExitRemainsWinnerWhenStdinFailsLater() throws Exception {
        OneShotSupervision supervision = supervision(exitedProcess(), Duration.ofSeconds(1));

        assertInstanceOf(OneShotSupervision.ProcessExited.class, supervision.await());

        supervision.stdinFailureHandler().accept(new IllegalStateException("late failure"));
        assertInstanceOf(OneShotSupervision.ProcessExited.class, supervision.await());
    }

    @Test
    void completedOutputFailureWinsBeforeProcessExitOrTimeout() throws Exception {
        IllegalStateException expected = new IllegalStateException("stdout failed");
        OneShotSupervision supervision = supervision(liveProcess(), Duration.ofSeconds(1));

        supervision.outputFailureHandler().accept(expected);

        OneShotSupervision.OutputFailure signal =
                assertInstanceOf(OneShotSupervision.OutputFailure.class, supervision.await());
        assertSame(expected, signal.failure());
    }

    @Test
    void timeoutWinsWhenNoEarlierSupervisionSignalOccurs() throws Exception {
        OneShotSupervision supervision = supervision(liveProcess(), Duration.ofMillis(1));

        assertInstanceOf(OneShotSupervision.TimedOut.class, supervision.await());
    }

    @Test
    void selectedSignalWinsConcurrentInterruptionAndInterruptStatusIsRestored() throws Exception {
        AtomicReference<Runnable> beforeInterruption = new AtomicReference<>();
        InterruptingWaitProcess process = new InterruptingWaitProcess(beforeInterruption);
        OneShotSupervision supervision = supervision(process, Duration.ofSeconds(1));
        IllegalStateException expected = new IllegalStateException("stdin failed");
        beforeInterruption.set(() -> supervision.stdinFailureHandler().accept(expected));

        try {
            OneShotSupervision.StdinFailure signal =
                    assertInstanceOf(OneShotSupervision.StdinFailure.class, supervision.await());

            assertSame(expected, signal.failure());
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void interruptionPropagatesWhenNoSupervisionSignalWasSelected() {
        AtomicReference<Runnable> beforeInterruption = new AtomicReference<>(() -> {});
        InterruptingWaitProcess process = new InterruptingWaitProcess(beforeInterruption);
        OneShotSupervision supervision = supervision(process, Duration.ofSeconds(1));

        InterruptedException actual = assertThrows(InterruptedException.class, supervision::await);

        assertSame(process.interruption, actual);
    }

    private static OneShotSupervision supervision(Process process, Duration timeout) {
        return new OneShotSupervision(process, OneShotDeadline.start(timeout), new LiveDescendantSnapshot());
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
