/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.diagnostics.DiagnosticEvent;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.FutureTask;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class ProcessKernelFailureOutputAndSupervisionCleanupTest extends ProcessKernelProcessFixtureSupport {

    @Test
    void elapsedDurationUsesInjectedMonotonicTimeAndClampsBackwardReadings() {
        AtomicLong nanoTime = new AtomicLong(100);
        TerminalProcess process =
                new TerminalProcess(new TrackingInputStream(), new TrackingInputStream(), new TrackingOutputStream());
        ProcessKernel kernel = kernel(
                ignored -> {},
                (launchPlan, stdio) -> process,
                new BoundedCloseDispatcher(3, 3),
                Duration.ofSeconds(1),
                () -> nanoTime.getAndSet(50));

        CommandResult result = kernel.run(executionPlan(
                DiagnosticsSettings.disabled(), Optional.empty(), OutputMode.SEPARATE, Duration.ofSeconds(1)));

        assertEquals(Duration.ZERO, result.elapsed());
    }

    @Test
    void launchErrorRetainsIdentityAndEmitsOneSafeProcessFailure() throws Exception {
        AssertionError launchFailure = new AssertionError("launch failed");
        CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch failureDelivered = new CountDownLatch(1);
        ExecutionPlan plan = executionPlan(
                StandardCharsets.UTF_8, DiagnosticsSettings.disabled().withListener(event -> {
                    events.add(event);
                    if (event.type() == DiagnosticEventType.PROCESS_FAILED) {
                        failureDelivered.countDown();
                    }
                }));
        ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> {
            throw launchFailure;
        });

        AssertionError thrown = assertThrows(AssertionError.class, () -> kernel.run(plan));

        assertSame(launchFailure, thrown);
        assertTrue(failureDelivered.await(1, TimeUnit.SECONDS));
        List<DiagnosticEvent> failures = events.stream()
                .filter(event -> event.type() == DiagnosticEventType.PROCESS_FAILED)
                .toList();
        assertEquals(1, failures.size());
        assertEquals(
                AssertionError.class.getName(), failures.get(0).attributes().get("error"));
    }

    @Test
    void postStartErrorRetainsIdentityWhenDiagnosticListenerAlsoFails() throws Exception {
        AssertionError operationFailure = new AssertionError("post-start failed");
        CountDownLatch listenerCalled = new CountDownLatch(1);
        CleanupProcess process = new CleanupProcess(new CloseCountingOutputStream(null));
        ExecutionPlan plan = executionPlan(
                StandardCharsets.UTF_8, DiagnosticsSettings.disabled().withListener(event -> {
                    if (event.type() == DiagnosticEventType.PROCESS_FAILED) {
                        listenerCalled.countDown();
                        throw new AssertionError("listener failed");
                    }
                }));
        ProcessKernel kernel = kernel(
                ignored -> {
                    throw operationFailure;
                },
                (launchPlan, stdio) -> process);

        AssertionError thrown = assertThrows(AssertionError.class, () -> kernel.run(plan));

        assertSame(operationFailure, thrown);
        assertTrue(listenerCalled.await(1, TimeUnit.SECONDS));
        assertFalse(process.isAlive());
    }

    @Test
    void decodeFailureElapsedIsSampledAfterBlockedSupervisionCleanup() throws Exception {
        AtomicBoolean cleanupFinished = new AtomicBoolean();
        AtomicBoolean failureObservedAfterCleanup = new AtomicBoolean();
        List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        BlockingCleanupInputStream stdout = new BlockingCleanupInputStream(cleanupFinished);
        TerminalProcess process = new TerminalProcess(stdout, new TrackingInputStream(), new TrackingOutputStream());
        AtomicInteger nanoReads = new AtomicInteger();
        ProcessKernel kernel = kernel(
                ignored -> {},
                (launchPlan, stdio) -> process,
                new BoundedCloseDispatcher(3, 3),
                Duration.ofSeconds(1),
                () -> nanoReads.getAndIncrement() == 0 ? 100L : cleanupFinished.get() ? 500L : 200L);
        ExecutionPlan plan = executionPlan(
                DiagnosticsSettings.disabled().withListener(event -> {
                    events.add(event);
                    if (event.type() == DiagnosticEventType.PROCESS_FAILED) {
                        failureObservedAfterCleanup.set(cleanupFinished.get());
                    }
                }),
                Optional.empty(),
                OutputMode.SEPARATE,
                Duration.ofSeconds(1));
        FutureTask<Throwable> execution = new FutureTask<>(() -> captureFailure(() -> kernel.run(plan)));
        Thread runner = new Thread(execution, "procwright-decode-cleanup-elapsed-test");
        runner.setDaemon(true);
        runner.start();
        try {
            assertTrue(stdout.awaitClose());
            assertFalse(execution.isDone(), "decode failure must wait for physical supervision cleanup");
        } finally {
            stdout.releaseClose();
        }

        CommandExecutionException failure = (CommandExecutionException) execution.get(1, TimeUnit.SECONDS);
        assertEquals(CommandExecutionException.Reason.DECODE_ERROR, failure.reason());
        assertEquals(Duration.ofNanos(400), failure.result().orElseThrow().elapsed());
        assertTrue(cleanupFinished.get());
        assertTrue(eventually(() -> terminalCount(events, DiagnosticEventType.PROCESS_FAILED) == 1));
        assertTrue(failureObservedAfterCleanup.get());
        assertEquals(0, terminalCount(events, DiagnosticEventType.PROCESS_EXITED));
    }

    @Test
    void asynchronousStdoutAndStderrErrorsRetainIdentityAndOwnCloseFailures() throws Exception {
        for (boolean stdoutFails : new boolean[] {true, false}) {
            AssertionError readFailure = new AssertionError(stdoutFails ? "stdout read" : "stderr read");
            AssertionError closeFailure = new AssertionError(stdoutFails ? "stdout close" : "stderr close");
            FailingReadInputStream failing = new FailingReadInputStream(readFailure, closeFailure);
            TrackingInputStream other = new TrackingInputStream();
            TerminalProcess process = stdoutFails
                    ? new TerminalProcess(failing, other, new TrackingOutputStream())
                    : new TerminalProcess(other, failing, new TrackingOutputStream());
            List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
            ExecutionPlan plan = executionPlan(
                    DiagnosticsSettings.disabled().withListener(events::add),
                    Optional.empty(),
                    OutputMode.SEPARATE,
                    Duration.ofSeconds(1));
            ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> process);

            Error actual = assertThrows(Error.class, () -> kernel.run(plan));

            assertSame(readFailure, FailureAggregation.primary(actual));
            assertTrue(FailureAggregation.sources(actual).contains(closeFailure));
            assertEquals(0, readFailure.getSuppressed().length);
            assertTrue(eventually(() -> terminalCount(events, DiagnosticEventType.PROCESS_FAILED) == 1));
            assertEquals(0, terminalCount(events, DiagnosticEventType.PROCESS_EXITED));
            assertEquals(
                    AssertionError.class.getName(),
                    events.stream()
                            .filter(event -> event.type() == DiagnosticEventType.PROCESS_FAILED)
                            .findFirst()
                            .orElseThrow()
                            .attributes()
                            .get("error"));
            assertEquals(1, failing.closeCalls());
            assertEquals(1, other.closeCalls());
            assertEquals(1, process.stdin.closeCalls());
        }
    }

    @Test
    void successfulCaptureClosesEveryStableStreamExactlyOnceInSeparateAndMergedModes() {
        for (OutputMode outputMode : OutputMode.values()) {
            TrackingOutputStream stdin = new TrackingOutputStream();
            TrackingInputStream stdout = new TrackingInputStream();
            TrackingInputStream stderr = new TrackingInputStream();
            TerminalProcess process = new TerminalProcess(stdout, stderr, stdin);
            ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> process);

            kernel.run(
                    executionPlan(DiagnosticsSettings.disabled(), Optional.empty(), outputMode, Duration.ofSeconds(1)));

            assertEquals(1, stdin.closeCalls());
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
            assertEquals(1, process.stdinGets.get());
            assertEquals(1, process.stdoutGets.get());
            assertEquals(1, process.stderrGets.get());
        }
    }

    @Test
    void exhaustedCloseCapacityFailsBeforeOneShotProcessPublicationOrStreamObservation() throws Exception {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2);
        BoundedCloseDispatcher.Reservation occupied = dispatcher.reserve(3);
        TerminalProcess process = new TerminalProcess(
                new TrackingInputStream(), new TrackingInputStream(), new TrackingOutputStream(), true);
        CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> process, dispatcher, Duration.ofSeconds(1));
        try {
            assertThrows(
                    RejectedExecutionException.class,
                    () -> kernel.run(executionPlan(
                            DiagnosticsSettings.disabled().withListener(events::add),
                            Optional.empty(),
                            OutputMode.SEPARATE,
                            Duration.ofMillis(10))));

            assertFalse(process.isAlive());
            assertEquals(0, process.stdinGets.get());
            assertEquals(0, process.stdoutGets.get());
            assertEquals(0, process.stderrGets.get());
            assertTrue(eventually(() -> terminalCount(events, DiagnosticEventType.PROCESS_FAILED) == 1));
            assertEquals(0, terminalCount(events, DiagnosticEventType.PROCESS_STARTED));
            assertEquals(0, terminalCount(events, DiagnosticEventType.PROCESS_EXITED));
        } finally {
            occupied.release();
        }
    }

    @Test
    void timedOutRunConsumesItsPreReservedClosesAtFullDispatcherCapacity() {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(1, 2);
        TerminalProcess process = new TerminalProcess(
                new TrackingInputStream(), new TrackingInputStream(), new TrackingOutputStream(), true);
        ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> process, dispatcher, Duration.ofSeconds(1));

        assertTrue(kernel.run(executionPlan(
                        DiagnosticsSettings.disabled(), Optional.empty(), OutputMode.SEPARATE, Duration.ofMillis(10)))
                .timedOut());

        assertEquals(0, dispatcher.outstandingCount());
        assertEquals(1, process.stdin.closeCalls());
        assertEquals(1, process.stdout.closeCalls());
        assertEquals(1, process.stderr.closeCalls());
    }

    @Test
    void timeoutCleanupFailureUsesThePublicExecutionExceptionContract() throws Exception {
        IllegalStateException cleanupFailure = new IllegalStateException("graceful destroy failed");
        TerminalProcess process =
                new TerminalProcess(
                        new TrackingInputStream(), new TrackingInputStream(), new TrackingOutputStream(), true) {
                    @Override
                    public void destroy() {
                        super.destroy();
                        throw cleanupFailure;
                    }
                };
        List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> process);

        CommandExecutionException failure = assertThrows(
                CommandExecutionException.class,
                () -> kernel.run(executionPlan(
                        DiagnosticsSettings.disabled().withListener(events::add),
                        Optional.empty(),
                        OutputMode.SEPARATE,
                        Duration.ofMillis(10))));

        assertEquals(CommandExecutionException.Reason.RUNTIME_FAILURE, failure.reason());
        assertSame(cleanupFailure, failure.getCause());
        assertEquals(1, terminalCount(events, DiagnosticEventType.TIMEOUT_REACHED));
        assertTrue(eventually(() -> terminalCount(events, DiagnosticEventType.PROCESS_FAILED) == 1));
        assertEquals(1, process.stdin.closeCalls());
        assertEquals(1, process.stdout.closeCalls());
        assertEquals(1, process.stderr.closeCalls());
    }

    @Test
    void acceptedFallbackCloseKeepsOneShotCompletionPendingUntilPhysicalSettlement() throws Exception {
        IllegalStateException startFailure = new IllegalStateException("stdin close starter failed");
        BlockingCloseOutputStream stdin = new BlockingCloseOutputStream();
        TerminalProcess process = new TerminalProcess(new TrackingInputStream(), new TrackingInputStream(), stdin);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 1, (name, task) -> {
            if (name.contains("stdin")) {
                throw startFailure;
            }
            Threading.start(name, task);
        });
        ProcessKernel kernel = kernel(ignored -> {}, (launchPlan, stdio) -> process, dispatcher, Duration.ofSeconds(2));
        FutureTask<CommandResult> run = new FutureTask<>(() -> kernel.run(executionPlan(StandardCharsets.UTF_8)));
        Thread caller = new Thread(run, "procwright-kernel-blocked-fallback-test");
        caller.setDaemon(true);
        caller.start();
        try {
            assertTrue(stdin.awaitClose());
            assertFalse(
                    run.isDone(), "ProcessKernel.awaitClose must retain terminal publication until fallback settles");
        } finally {
            stdin.release();
        }

        java.util.concurrent.ExecutionException terminal =
                assertThrows(java.util.concurrent.ExecutionException.class, () -> run.get(1, TimeUnit.SECONDS));
        CommandExecutionException failure = assertInstanceOf(CommandExecutionException.class, terminal.getCause());
        assertEquals(CommandExecutionException.Reason.RUNTIME_FAILURE, failure.reason());
        assertSame(startFailure, failure.getCause());
        assertEquals(1, stdin.closeCalls());
        assertTrue(eventually(() ->
                dispatcher.activeCount() == 0 && dispatcher.pendingCount() == 0 && dispatcher.outstandingCount() == 0));
    }

    static final class CleanupProcess extends Process {

        final OutputStream stdin;
        final AtomicBoolean alive = new AtomicBoolean(true);

        CleanupProcess(OutputStream stdin) {
            this.stdin = stdin;
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
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
            alive.set(false);
            return 137;
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) {
            return !alive.get();
        }

        @Override
        public int exitValue() {
            if (alive.get()) {
                throw new IllegalThreadStateException("process is alive");
            }
            return 137;
        }

        @Override
        public void destroy() {
            alive.set(false);
        }

        @Override
        public Process destroyForcibly() {
            alive.set(false);
            return this;
        }

        @Override
        public boolean isAlive() {
            return alive.get();
        }

        @Override
        public ProcessHandle toHandle() {
            throw new UnsupportedOperationException("process handles are unavailable");
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }
    }

    static final class CloseCountingOutputStream extends OutputStream {

        final Error failure;
        final AtomicInteger closes = new AtomicInteger();
        final CountDownLatch closed = new CountDownLatch(1);

        CloseCountingOutputStream(Error failure) {
            this.failure = failure;
        }

        @Override
        public void write(int value) {}

        @Override
        public void close() {
            closes.incrementAndGet();
            closed.countDown();
            if (failure != null) {
                throw failure;
            }
        }

        boolean awaitClose() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }

        final int closeCalls() {
            return closes.get();
        }
    }

    static final class BlockingCloseOutputStream extends TrackingOutputStream {

        final CountDownLatch closeStarted = new CountDownLatch(1);
        final CountDownLatch release = new CountDownLatch(1);

        @Override
        public void write(int value) {}

        @Override
        public void close() {
            super.close();
            closeStarted.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    release.await();
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        boolean awaitClose() throws InterruptedException {
            return closeStarted.await(1, TimeUnit.SECONDS);
        }

        void release() {
            release.countDown();
        }
    }

    static final class BlockingCleanupInputStream extends TrackingInputStream {

        final AtomicBoolean cleanupFinished;
        final AtomicBoolean byteReturned = new AtomicBoolean();
        final CountDownLatch closeStarted = new CountDownLatch(1);
        final CountDownLatch releaseClose = new CountDownLatch(1);

        BlockingCleanupInputStream(AtomicBoolean cleanupFinished) {
            this.cleanupFinished = cleanupFinished;
        }

        @Override
        public int read() {
            return byteReturned.compareAndSet(false, true) ? 0xC3 : -1;
        }

        @Override
        public void close() {
            super.close();
            closeStarted.countDown();
            boolean interrupted = false;
            while (true) {
                try {
                    releaseClose.await();
                    break;
                } catch (InterruptedException exception) {
                    interrupted = true;
                }
            }
            cleanupFinished.set(true);
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }

        boolean awaitClose() throws InterruptedException {
            return closeStarted.await(1, TimeUnit.SECONDS);
        }

        void releaseClose() {
            releaseClose.countDown();
        }
    }

    static final class FailingReadInputStream extends TrackingInputStream {

        final AssertionError readFailure;
        final AssertionError closeFailure;

        FailingReadInputStream(AssertionError readFailure, AssertionError closeFailure) {
            this.readFailure = readFailure;
            this.closeFailure = closeFailure;
        }

        @Override
        public int read() {
            throw readFailure;
        }

        @Override
        public void close() {
            super.close();
            throw closeFailure;
        }
    }
}
