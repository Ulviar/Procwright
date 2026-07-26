/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.EnvironmentPolicy;
import io.github.ulviar.procwright.command.OutputMode;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.diagnostics.DiagnosticEvent;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.LaunchPlan;
import io.github.ulviar.procwright.internal.SessionExecutionPlan;
import io.github.ulviar.procwright.internal.StreamExecutionPlan;
import io.github.ulviar.procwright.internal.Threading;
import io.github.ulviar.procwright.session.StreamSession;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.PtyRequest;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import io.github.ulviar.procwright.terminal.TerminalSize;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class StreamRuntimeStartupTest extends StreamRuntimeTestSupport {

    @Test
    void openUsesTheStablePidCapturedBySessionRuntimeOnlyOnce() throws Exception {
        AssertionError secondPidFailure = new AssertionError("pid queried more than once");
        StatefulPidProcess process = new StatefulPidProcess(secondPidFailure);
        CountDownLatch processStarted = new CountDownLatch(1);
        AtomicInteger processFailures = new AtomicInteger();
        DiagnosticsSettings settings = DiagnosticsSettings.disabled().withListener(event -> {
            if (event.type() == DiagnosticEventType.PROCESS_STARTED) {
                processStarted.countDown();
            }
            if (event.type() == DiagnosticEventType.PROCESS_FAILED) {
                processFailures.incrementAndGet();
            }
        });
        StreamSession stream = null;
        try {
            stream = StreamRuntime.open(ptyPlan(process, settings));

            assertTrue(processStarted.await(1, TimeUnit.SECONDS));
            assertEquals(1, process.pidCalls());
            assertEquals(0, processFailures.get());
            assertTrue(process.isAlive());
        } finally {
            if (stream != null) {
                stream.close();
            } else {
                process.destroyForcibly();
            }
        }

        assertTrue(process.awaitDestroyed());
        assertFalse(process.isAlive());
    }

    @Test
    void listenClosesStdinDuringConstruction() throws Exception {
        CloseCountingOutputStream stdin = new CloseCountingOutputStream();
        ReadinessInputStream stdout = new ReadinessInputStream();
        ControllableProcess process = new ControllableProcess(stdout, InputStream.nullInputStream(), null, stdin);
        DefaultSession rawSession = session(process);
        StreamSession stream = new DefaultStreamSession(rawSession, plan(), diagnostics());
        try {
            assertTrue(stdout.awaitReadStarted(), "stdout pump did not reach its readiness barrier");
            assertTrue(eventually(() -> stdin.closeCalls() == 1), "listen must close stdin during construction");
        } finally {
            stream.close();
        }

        assertEquals(1, stdin.closeCalls());
    }

    @Test
    void constructionErrorStopsTheAlreadyOpenedSession() {
        ControllableProcess process = new ControllableProcess();
        DefaultSession session = SessionTestFixtures.open(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                diagnostics());
        AssertionError constructionFailure = new AssertionError("stream construction failed");

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> StreamRuntime.finishOpen(session, plan(), diagnostics(), (rawSession, plan, events) -> {
                    throw constructionFailure;
                }));

        assertSame(constructionFailure, thrown);
        assertFalse(process.isAlive(), "failed stream construction must close the opened process");
    }

    @Test
    void postCommitConstructionFailureClosesOwnedOutputAndStopsPumpsExactlyOnce() throws Exception {
        AtomicBoolean processAlive = new AtomicBoolean(true);
        ConstructionBlockingInputStream stdout = new ConstructionBlockingInputStream(processAlive);
        ConstructionBlockingInputStream stderr = new ConstructionBlockingInputStream(processAlive);
        ControllableProcess process =
                new ControllableProcess(stdout, stderr, null, OutputStream.nullOutputStream(), processAlive);
        AssertionError constructionFailure = new AssertionError("stdin close scheduling failed");
        CountDownLatch pumpsStopped = new CountDownLatch(2);
        CopyOnWriteArrayList<Thread> pumpThreads = new CopyOnWriteArrayList<>();
        PumpStarter trackingStarter = (namePrefix, task) -> {
            Thread thread = io.github.ulviar.procwright.internal.Threading.start(namePrefix, () -> {
                try {
                    task.run();
                } finally {
                    pumpsStopped.countDown();
                }
            });
            pumpThreads.add(thread);
            return thread;
        };
        CopyOnWriteArrayList<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
        CountDownLatch shutdownDelivered = new CountDownLatch(1);
        CountDownLatch processExitedDelivered = new CountDownLatch(1);
        DiagnosticEmitter eventDiagnostics = DiagnosticEmitter.of(
                DiagnosticsSettings.disabled().withListener(event -> {
                    events.add(event);
                    if (event.type() == DiagnosticEventType.SHUTDOWN_REQUESTED) {
                        shutdownDelivered.countDown();
                    }
                    if (event.type() == DiagnosticEventType.PROCESS_EXITED) {
                        processExitedDelivered.countDown();
                    }
                }),
                "listen",
                CommandEcho.empty());
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(3, 3, (name, task) -> {
            if (name.startsWith("procwright-process-stdin-close-")) {
                awaitUninterruptibly(stdout.readStarted);
                awaitUninterruptibly(stderr.readStarted);
                throw constructionFailure;
            }
            Threading.start(name, task);
        });
        DefaultSession rawSession = DefaultSession.openTransactionally(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                diagnostics(),
                () -> {},
                closeDispatcher,
                Threading::start);
        eventDiagnostics.emit(DiagnosticEventType.COMMAND_PREPARED);
        eventDiagnostics.emit(DiagnosticEventType.PROCESS_STARTED, DiagnosticEmitter.attributes("pid", "42"));

        AssertionError thrown = assertThrows(
                AssertionError.class,
                () -> StreamRuntime.finishOpen(
                        rawSession,
                        plan(),
                        eventDiagnostics,
                        (session, streamPlan, streamDiagnostics) -> new DefaultStreamSession(
                                session,
                                streamPlan,
                                streamDiagnostics,
                                StreamSessionTestDependencies.withPumpStarter(trackingStarter))));

        assertSame(constructionFailure, thrown);
        assertTrue(process.awaitDestroyed(), "process cleanup must complete before owned output closes");
        assertTrue(stdout.awaitClose());
        assertTrue(stderr.awaitClose());
        assertTrue(pumpsStopped.await(1, TimeUnit.SECONDS), "committed pumps must terminate");
        assertTrue(pumpThreads.stream().noneMatch(Thread::isAlive));
        assertTrue(stdout.destroyedBeforeClose());
        assertTrue(stderr.destroyedBeforeClose());
        assertEquals(1, stdout.closeCalls());
        assertEquals(1, stderr.closeCalls());

        rawSession.close();
        assertTrue(shutdownDelivered.await(2, TimeUnit.SECONDS));
        eventDiagnostics.emit(DiagnosticEventType.PROCESS_EXITED, DiagnosticEmitter.attributes("timedOut", "false"));
        assertTrue(processExitedDelivered.await(2, TimeUnit.SECONDS));
        assertEquals(
                List.of(
                        DiagnosticEventType.COMMAND_PREPARED,
                        DiagnosticEventType.PROCESS_STARTED,
                        DiagnosticEventType.PROCESS_FAILED,
                        DiagnosticEventType.SHUTDOWN_REQUESTED,
                        DiagnosticEventType.PROCESS_EXITED),
                events.stream().map(DiagnosticEvent::type).toList());
        assertEquals(1, stdout.closeCalls(), "raw-session fallback must not physically close owned stdout");
        assertEquals(1, stderr.closeCalls(), "raw-session fallback must not physically close owned stderr");
    }

    @Test
    void cleanupFailureIsReportedWithoutMutatingTheConstructionFailure() throws Exception {
        AssertionError cleanupFailure = new AssertionError("stream cleanup failed");
        CountDownLatch reported = new CountDownLatch(1);
        AtomicInteger matchingReports = new AtomicInteger();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            if (failure == cleanupFailure) {
                matchingReports.incrementAndGet();
                reported.countDown();
            }
        });

        try {
            StreamRuntime.closePreserving(() -> {
                throw cleanupFailure;
            });
            assertTrue(reported.await(1, TimeUnit.SECONDS));
            assertEquals(1, matchingReports.get());
            assertEquals(0, cleanupFailure.getSuppressed().length);
        } finally {
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    private static StreamExecutionPlan ptyPlan(Process process, DiagnosticsSettings diagnostics) {
        PtyProvider provider = new PtyProvider() {
            @Override
            public boolean available() {
                return true;
            }

            @Override
            public String description() {
                return "stateful PID test PTY";
            }

            @Override
            public Process start(PtyRequest request) {
                return process;
            }
        };
        LaunchPlan launchPlan = new LaunchPlan(
                List.of("stateful-pid"),
                Optional.empty(),
                EnvironmentPolicy.INHERIT,
                Map.of(),
                OutputMode.SEPARATE,
                TerminalPolicy.REQUIRED);
        SessionExecutionPlan sessionPlan = new SessionExecutionPlan(
                launchPlan,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                Duration.ZERO,
                StandardCharsets.UTF_8,
                provider,
                TerminalSize.defaults());
        return new StreamExecutionPlan(sessionPlan, Duration.ZERO, 1024, chunk -> {}, diagnostics);
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

    private static final class StatefulPidProcess extends Process {

        private final AssertionError secondPidFailure;
        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final AtomicInteger pidCalls = new AtomicInteger();
        private final CountDownLatch destroyed = new CountDownLatch(1);

        private StatefulPidProcess(AssertionError secondPidFailure) {
            this.secondPidFailure = secondPidFailure;
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
            try {
                return exit.get();
            } catch (ExecutionException exception) {
                throw new AssertionError(exception.getCause());
            }
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            try {
                exit.get(timeout, unit);
                return true;
            } catch (TimeoutException exception) {
                return false;
            } catch (ExecutionException exception) {
                throw new AssertionError(exception.getCause());
            }
        }

        @Override
        public int exitValue() {
            Integer value = exit.getNow(null);
            if (value == null) {
                throw new IllegalThreadStateException("process is alive");
            }
            return value;
        }

        @Override
        public void destroy() {
            exit.complete(143);
            destroyed.countDown();
        }

        @Override
        public Process destroyForcibly() {
            destroy();
            return this;
        }

        @Override
        public boolean isAlive() {
            return !exit.isDone();
        }

        @Override
        public long pid() {
            if (pidCalls.incrementAndGet() > 1) {
                throw secondPidFailure;
            }
            return 4242;
        }

        @Override
        public ProcessHandle toHandle() {
            throw new UnsupportedOperationException("process handles are unavailable");
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        private int pidCalls() {
            return pidCalls.get();
        }

        private boolean awaitDestroyed() throws InterruptedException {
            return destroyed.await(1, TimeUnit.SECONDS);
        }
    }

    private static final class CloseCountingOutputStream extends OutputStream {

        private final AtomicInteger closeCalls = new AtomicInteger();

        @Override
        public void write(int value) {}

        @Override
        public void close() {
            closeCalls.incrementAndGet();
        }

        private int closeCalls() {
            return closeCalls.get();
        }
    }

    private static final class ReadinessInputStream extends InputStream {

        private final CountDownLatch readStarted = new CountDownLatch(1);

        @Override
        public int read() {
            readStarted.countDown();
            return -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (length == 0) {
                return 0;
            }
            return read();
        }

        private boolean awaitReadStarted() throws InterruptedException {
            return readStarted.await(1, TimeUnit.SECONDS);
        }
    }

    private static final class ConstructionBlockingInputStream extends InputStream {

        private final AtomicBoolean processAlive;
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicBoolean destroyedBeforeClose = new AtomicBoolean();
        private final AtomicInteger closes = new AtomicInteger();

        private ConstructionBlockingInputStream(AtomicBoolean processAlive) {
            this.processAlive = processAlive;
        }

        @Override
        public int read() {
            readStarted.countDown();
            awaitUninterruptibly(closed);
            return -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            return length == 0 ? 0 : read();
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            destroyedBeforeClose.set(!processAlive.get());
            closed.countDown();
        }

        private boolean awaitClose() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }

        private boolean destroyedBeforeClose() {
            return destroyedBeforeClose.get();
        }

        private int closeCalls() {
            return closes.get();
        }
    }
}
