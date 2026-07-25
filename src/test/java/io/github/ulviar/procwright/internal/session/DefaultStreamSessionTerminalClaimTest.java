/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.diagnostics.DiagnosticEvent;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.FailureAggregation;
import io.github.ulviar.procwright.session.StreamException;
import io.github.ulviar.procwright.session.StreamExit;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestFactory;

final class DefaultStreamSessionTerminalClaimTest extends DefaultStreamSessionTestSupport {

    @Test
    void normalCompletionClaimRejectsALateTimeoutWithoutPostTerminalDiagnostics() throws Exception {
        ControllableProcess process =
                new ControllableProcess(InputStream.nullInputStream(), InputStream.nullInputStream());
        CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        DefaultStreamSession stream = new DefaultStreamSession(
                session(process),
                plan(chunk -> {}),
                DiagnosticEmitter.of(
                        DiagnosticsSettings.disabled().withListener(events::add),
                        "stream-race-test",
                        CommandEcho.empty()));
        try {
            process.complete(0);
            StreamExit result = stream.onExit().get(1, TimeUnit.SECONDS);

            stream.expireTimeout();

            assertFalse(result.timedOut());
            assertFalse(result.closed());
            assertTrue(eventually(() -> count(events, DiagnosticEventType.PROCESS_EXITED) == 1));
            assertEquals(0, count(events, DiagnosticEventType.TIMEOUT_REACHED));
            assertEquals(0, timeoutShutdownCount(events));
        } finally {
            stream.close();
        }
    }

    @Test
    void timeoutClaimWinsBeforeProcessCompletionAndPublishesOneConsistentOutcome() throws Exception {
        ControllableProcess process =
                new ControllableProcess(InputStream.nullInputStream(), InputStream.nullInputStream());
        CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        DefaultStreamSession stream = new DefaultStreamSession(
                session(process),
                plan(chunk -> {}),
                DiagnosticEmitter.of(
                        DiagnosticsSettings.disabled().withListener(events::add),
                        "stream-race-test",
                        CommandEcho.empty()));
        try {
            stream.expireTimeout();
            StreamExit result = stream.onExit().get(1, TimeUnit.SECONDS);

            assertTrue(result.timedOut());
            assertFalse(result.closed());
            assertTrue(eventually(() -> count(events, DiagnosticEventType.PROCESS_EXITED) == 1));
            assertEquals(1, count(events, DiagnosticEventType.TIMEOUT_REACHED));
            assertEquals(1, timeoutShutdownCount(events));
        } finally {
            stream.close();
        }
    }

    @Test
    void closeClaimRejectsLateTimeoutAndNormalCompletion() throws Exception {
        ControllableProcess process =
                new ControllableProcess(InputStream.nullInputStream(), InputStream.nullInputStream());
        CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        DefaultStreamSession stream = new DefaultStreamSession(
                session(process),
                plan(chunk -> {}),
                DiagnosticEmitter.of(
                        DiagnosticsSettings.disabled().withListener(events::add),
                        "stream-race-test",
                        CommandEcho.empty()));
        try {
            stream.close();
            stream.expireTimeout();
            process.complete(0);
            StreamExit result = stream.onExit().get(1, TimeUnit.SECONDS);

            assertFalse(result.timedOut());
            assertTrue(result.closed());
            assertTrue(eventually(() -> count(events, DiagnosticEventType.PROCESS_EXITED) == 1));
            assertEquals(0, count(events, DiagnosticEventType.TIMEOUT_REACHED));
            assertEquals(0, timeoutShutdownCount(events));
            assertEquals(1, shutdownCount(events, "close"));
        } finally {
            stream.close();
        }
    }

    @Test
    void failureClaimRejectsLateTimeoutAndNormalCompletion() throws Exception {
        GatedChunkInputStream stdout = new GatedChunkInputStream("x");
        ControllableProcess process = new ControllableProcess(stdout, InputStream.nullInputStream());
        IllegalStateException listenerFailure = new IllegalStateException("listener failed");
        CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        DefaultStreamSession stream = new DefaultStreamSession(
                session(process),
                plan(chunk -> {
                    throw listenerFailure;
                }),
                DiagnosticEmitter.of(
                        DiagnosticsSettings.disabled().withListener(events::add),
                        "stream-race-test",
                        CommandEcho.empty()));
        try {
            assertTrue(stdout.awaitReadStarted());
            stdout.release();
            ExecutionException failure = org.junit.jupiter.api.Assertions.assertThrows(
                    ExecutionException.class, () -> stream.onExit().get(1, TimeUnit.SECONDS));
            StreamException streamFailure = (StreamException) failure.getCause();
            assertSame(listenerFailure, streamFailure.getCause());

            stream.expireTimeout();
            process.complete(0);

            assertTrue(eventually(() -> count(events, DiagnosticEventType.PROCESS_FAILED) == 1));
            assertEquals(0, count(events, DiagnosticEventType.PROCESS_EXITED));
            assertEquals(0, count(events, DiagnosticEventType.TIMEOUT_REACHED));
            assertEquals(0, timeoutShutdownCount(events));
            assertEquals(1, shutdownCount(events, "failure"));
        } finally {
            stdout.release();
            stream.close();
        }
    }

    @TestFactory
    Stream<DynamicTest> controlOutcomeCompletesAfterLateNestedSessionFailure() {
        return Stream.of(ControlAction.values()).flatMap(control -> Stream.of(NestedFailureKind.values())
                .flatMap(failureKind -> Stream.of(PumpCompletionOrder.values())
                        .map(pumpOrder -> DynamicTest.dynamicTest(
                                control + " / " + failureKind + " / " + pumpOrder,
                                () -> assertControlOutcomeCompletesAfterLateNestedSessionFailure(
                                        control, failureKind, pumpOrder)))));
    }

    private static void assertControlOutcomeCompletesAfterLateNestedSessionFailure(
            ControlAction control, NestedFailureKind failureKind, PumpCompletionOrder pumpOrder) throws Exception {
        GatedEofInputStream stdout = new GatedEofInputStream();
        GatedEofInputStream stderr = new GatedEofInputStream();
        FailingLivenessProcess process = new FailingLivenessProcess(stdout, stderr);
        TrackingPumpStarter pumpStarter = new TrackingPumpStarter();
        CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch controlSelected = new CountDownLatch(1);
        CountDownLatch processExitedDelivered = new CountDownLatch(1);
        DiagnosticEmitter eventDiagnostics = DiagnosticEmitter.of(
                DiagnosticsSettings.disabled().withListener(event -> {
                    events.add(event);
                    if (control.isSelectionEvent(event)) {
                        controlSelected.countDown();
                    }
                    if (event.type() == DiagnosticEventType.PROCESS_EXITED) {
                        processExitedDelivered.countDown();
                    }
                }),
                "stream-late-session-failure-test",
                CommandEcho.empty());
        DefaultStreamSession stream = new DefaultStreamSession(
                session(process),
                plan(chunk -> {}),
                eventDiagnostics,
                StreamSessionTestDependencies.withPumpStarter(pumpStarter));
        CompletableFuture<StreamExit> observedExit = stream.onExit();
        AtomicInteger exitCompletions = new AtomicInteger();
        CompletableFuture<StreamExit> observedExitContinuation =
                observedExit.whenComplete((ignored, failure) -> exitCompletions.incrementAndGet());
        Throwable expected = failureKind.newFailure();
        CopyOnWriteArrayList<Throwable> lateReports = new CopyOnWriteArrayList<>();
        CountDownLatch lateReportEntered = new CountDownLatch(1);
        CountDownLatch releaseLateReport = new CountDownLatch(1);
        FutureTask<Throwable> controlTask = new FutureTask<>(() -> captureFailure(() -> control.terminate(stream)));
        Thread controlThread = new Thread(controlTask, "stream-control-test");
        controlThread.setDaemon(true);
        controlThread.setUncaughtExceptionHandler((ignored, failure) -> {
            lateReports.add(failure);
            lateReportEntered.countDown();
            awaitUninterruptibly(releaseLateReport);
        });
        process.failLivenessOn(controlThread, expected);

        try {
            assertTrue(stdout.awaitReadStarted(), "stdout pump did not start");
            assertTrue(stderr.awaitReadStarted(), "stderr pump did not start");
            if (pumpOrder == PumpCompletionOrder.EOF_BEFORE_FAILURE) {
                stdout.releaseEof();
                stderr.releaseEof();
                assertTrue(pumpStarter.awaitCompletion(), "output pumps did not finish before control");
            }

            controlThread.start();
            assertTrue(controlSelected.await(1, TimeUnit.SECONDS), "control outcome was not selected");
            assertTrue(process.awaitLivenessFailure(), "nested session did not reach the liveness failure");
            process.releaseLivenessFailure();

            if (pumpOrder == PumpCompletionOrder.EOF_AFTER_FAILURE) {
                assertFalse(observedExit.isDone(), "stream exit completed before blocked pumps reached EOF");
                assertEquals(1L, lateReportEntered.getCount(), "late report preceded terminal stream accounting");
                stdout.releaseEof();
                stderr.releaseEof();
                assertTrue(pumpStarter.awaitCompletion(), "output pumps did not finish after nested failure");
            }

            StreamExit result = observedExitContinuation.get(1, TimeUnit.SECONDS);
            assertTrue(lateReportEntered.await(1, TimeUnit.SECONDS), "late nested failure was not reported");
            assertLateReport(failureKind, expected, lateReports.get(0));
            assertEquals(control == ControlAction.TIMEOUT, result.timedOut());
            assertEquals(control == ControlAction.CLOSE, result.closed());
            assertTrue(result.exitCode().isEmpty());
            assertEquals(1, exitCompletions.get());
            assertTrue(retainsDirectFailure(controlTask.get(1, TimeUnit.SECONDS), expected));

            releaseLateReport.countDown();
            assertTrue(eventually(() -> stdout.closeCalls() == 1 && stderr.closeCalls() == 1));

            stream.close();
            stream.expireTimeout();
            assertEquals(result, stream.onExit().get(1, TimeUnit.SECONDS));
            assertTrue(
                    processExitedDelivered.await(1, TimeUnit.SECONDS), "PROCESS_EXITED diagnostic was not delivered");
            assertEquals(1, exitCompletions.get());
            assertEquals(1, lateReports.size());
            List<DiagnosticEvent> processExitedEvents = events.stream()
                    .filter(event -> event.type() == DiagnosticEventType.PROCESS_EXITED)
                    .toList();
            assertEquals(1, processExitedEvents.size());
            assertEquals(
                    Boolean.toString(control == ControlAction.TIMEOUT),
                    processExitedEvents.get(0).attributes().get("timedOut"));
            assertFalse(processExitedEvents.get(0).attributes().containsKey("exitCode"));
            assertEquals(0, count(events, DiagnosticEventType.PROCESS_FAILED));
            assertEquals(control == ControlAction.TIMEOUT ? 1 : 0, count(events, DiagnosticEventType.TIMEOUT_REACHED));
            assertEquals(control == ControlAction.TIMEOUT ? 1 : 0, timeoutShutdownCount(events));
            assertEquals(control == ControlAction.CLOSE ? 1 : 0, shutdownCount(events, "close"));
            assertEquals(0, shutdownCount(events, "failure"));
        } finally {
            process.releaseLivenessFailure();
            releaseLateReport.countDown();
            stdout.releaseEof();
            stderr.releaseEof();
            process.complete(143);
            stream.close();
            controlThread.join(TimeUnit.SECONDS.toMillis(1));
        }
    }

    private static void assertLateReport(NestedFailureKind kind, Throwable expected, Throwable reported) {
        if (kind == NestedFailureKind.ERROR) {
            assertTrue(FailureAggregation.sources(reported).contains(expected));
            return;
        }
        assertTrue(reported instanceof StreamException);
        StreamException streamFailure = (StreamException) reported;
        assertEquals(StreamException.Reason.PROCESS_FAILED, streamFailure.reason());
        assertTrue(retainsDirectFailure(streamFailure.getCause(), expected));
    }

    private static boolean retainsDirectFailure(Throwable container, Throwable expected) {
        return container == expected
                || container.getCause() == expected
                || FailureAggregation.sources(container).contains(expected)
                || java.util.Arrays.asList(container.getSuppressed()).contains(expected);
    }

    private static Throwable captureFailure(Runnable action) {
        try {
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static int count(List<DiagnosticEvent> events, DiagnosticEventType type) {
        return Math.toIntExact(
                events.stream().filter(event -> event.type() == type).count());
    }

    private static int timeoutShutdownCount(List<DiagnosticEvent> events) {
        return shutdownCount(events, "timeout");
    }

    private static int shutdownCount(List<DiagnosticEvent> events, String reason) {
        return Math.toIntExact(events.stream()
                .filter(event -> event.type() == DiagnosticEventType.SHUTDOWN_REQUESTED)
                .filter(event -> reason.equals(event.attributes().get("reason")))
                .count());
    }

    private static boolean eventually(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(1).toNanos();
        while (!condition.getAsBoolean()) {
            if (deadline - System.nanoTime() <= 0) {
                return false;
            }
            Thread.sleep(5);
        }
        return true;
    }

    private static void awaitUninterruptibly(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static final class GatedChunkInputStream extends InputStream {

        private final byte[] bytes;
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch chunkReturned = new CountDownLatch(1);
        private final AtomicBoolean delivered = new AtomicBoolean();
        private final AtomicInteger closeCalls = new AtomicInteger();
        private final AtomicReference<Thread> readerThread = new AtomicReference<>();

        private GatedChunkInputStream(String text) {
            this.bytes = text.getBytes(StandardCharsets.UTF_8);
        }

        @Override
        public int read() {
            byte[] single = new byte[1];
            int count = read(single, 0, 1);
            return count < 0 ? -1 : Byte.toUnsignedInt(single[0]);
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            if (length == 0) {
                return 0;
            }
            readerThread.compareAndSet(null, Thread.currentThread());
            readStarted.countDown();
            awaitUninterruptibly(release);
            if (!delivered.compareAndSet(false, true)) {
                return -1;
            }
            int count = Math.min(length, bytes.length);
            System.arraycopy(bytes, 0, target, offset, count);
            chunkReturned.countDown();
            return count;
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
            release.countDown();
        }

        private boolean awaitReadStarted() throws InterruptedException {
            return readStarted.await(1, TimeUnit.SECONDS);
        }

        private boolean awaitChunkReturned() throws InterruptedException {
            return chunkReturned.await(1, TimeUnit.SECONDS);
        }

        private Thread readerThread() {
            return readerThread.get();
        }

        private void release() {
            release.countDown();
        }

        private int closeCalls() {
            return closeCalls.get();
        }
    }

    private static final class GatedEofInputStream extends InputStream {

        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch releaseEof = new CountDownLatch(1);
        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public int read() {
            readStarted.countDown();
            awaitUninterruptibly(releaseEof);
            return -1;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            if (length == 0) {
                return 0;
            }
            return read();
        }

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }

        private boolean awaitReadStarted() throws InterruptedException {
            return readStarted.await(1, TimeUnit.SECONDS);
        }

        private void releaseEof() {
            releaseEof.countDown();
        }

        private int closeCalls() {
            return closeCalls.get();
        }
    }

    private static final class TrackingPumpStarter implements PumpStarter {

        private final CountDownLatch completed = new CountDownLatch(2);
        private final AtomicInteger sequence = new AtomicInteger();

        @Override
        public Thread start(String namePrefix, Runnable task) {
            Thread thread = new Thread(
                    () -> {
                        try {
                            task.run();
                        } finally {
                            completed.countDown();
                        }
                    },
                    namePrefix + sequence.getAndIncrement());
            thread.setDaemon(true);
            thread.start();
            return thread;
        }

        private boolean awaitCompletion() throws InterruptedException {
            return completed.await(1, TimeUnit.SECONDS);
        }
    }

    private enum ControlAction {
        CLOSE,
        TIMEOUT;

        private void terminate(DefaultStreamSession stream) {
            switch (this) {
                case CLOSE -> stream.close();
                case TIMEOUT -> stream.expireTimeout();
            }
        }

        private boolean isSelectionEvent(DiagnosticEvent event) {
            return switch (this) {
                case CLOSE ->
                    event.type() == DiagnosticEventType.SHUTDOWN_REQUESTED
                            && "close".equals(event.attributes().get("reason"));
                case TIMEOUT -> event.type() == DiagnosticEventType.TIMEOUT_REACHED;
            };
        }
    }

    private enum NestedFailureKind {
        RUNTIME,
        ERROR;

        private Throwable newFailure() {
            return switch (this) {
                case RUNTIME -> new IllegalStateException("liveness failed");
                case ERROR -> new AssertionError("liveness failed");
            };
        }
    }

    private enum PumpCompletionOrder {
        EOF_BEFORE_FAILURE,
        EOF_AFTER_FAILURE
    }

    private static final class FailingLivenessProcess extends Process {

        private final InputStream stdout;
        private final InputStream stderr;
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final AtomicBoolean alive = new AtomicBoolean(true);
        private final AtomicReference<Thread> failingThread = new AtomicReference<>();
        private final AtomicReference<Throwable> livenessFailure = new AtomicReference<>();
        private final CountDownLatch livenessFailureEntered = new CountDownLatch(1);
        private final CountDownLatch releaseLivenessFailure = new CountDownLatch(1);

        private FailingLivenessProcess(InputStream stdout, InputStream stderr) {
            this.stdout = stdout;
            this.stderr = stderr;
        }

        @Override
        public OutputStream getOutputStream() {
            return OutputStream.nullOutputStream();
        }

        @Override
        public InputStream getInputStream() {
            return stdout;
        }

        @Override
        public InputStream getErrorStream() {
            return stderr;
        }

        @Override
        public int waitFor() throws InterruptedException {
            try {
                return exit.get();
            } catch (ExecutionException failure) {
                throw new AssertionError(failure.getCause());
            }
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            try {
                exit.get(timeout, unit);
                return true;
            } catch (TimeoutException ignored) {
                return false;
            } catch (ExecutionException failure) {
                throw new AssertionError(failure.getCause());
            }
        }

        @Override
        public int exitValue() {
            Integer value = exit.getNow(null);
            if (value == null) {
                throw new IllegalThreadStateException("process is still running");
            }
            return value;
        }

        @Override
        public void destroy() {}

        @Override
        public Process destroyForcibly() {
            return this;
        }

        @Override
        public boolean isAlive() {
            if (Thread.currentThread() == failingThread.get()) {
                livenessFailureEntered.countDown();
                awaitUninterruptibly(releaseLivenessFailure);
                throwUnchecked(livenessFailure.get());
            }
            return alive.get();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        private void failLivenessOn(Thread thread, Throwable failure) {
            failingThread.set(thread);
            livenessFailure.set(failure);
        }

        private boolean awaitLivenessFailure() throws InterruptedException {
            return livenessFailureEntered.await(1, TimeUnit.SECONDS);
        }

        private void releaseLivenessFailure() {
            releaseLivenessFailure.countDown();
        }

        private void complete(int exitCode) {
            alive.set(false);
            exit.complete(exitCode);
        }

        private static void throwUnchecked(Throwable failure) {
            if (failure instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (failure instanceof Error error) {
                throw error;
            }
            throw new AssertionError("unexpected checked liveness failure", failure);
        }
    }
}
