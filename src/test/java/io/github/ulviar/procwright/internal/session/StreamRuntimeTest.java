/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
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
import io.github.ulviar.procwright.internal.LaunchMode;
import io.github.ulviar.procwright.internal.LaunchPlan;
import io.github.ulviar.procwright.internal.SessionExecutionPlan;
import io.github.ulviar.procwright.internal.StreamExecutionPlan;
import io.github.ulviar.procwright.internal.Threading;
import io.github.ulviar.procwright.session.StreamException;
import io.github.ulviar.procwright.session.StreamListener;
import io.github.ulviar.procwright.session.StreamSession;
import io.github.ulviar.procwright.terminal.PtyProvider;
import io.github.ulviar.procwright.terminal.PtyRequest;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import io.github.ulviar.procwright.terminal.TerminalSize;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class StreamRuntimeTest {

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
        DefaultSession session = new DefaultSession(
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
        CountDownLatch diagnosticBarrier = new CountDownLatch(1);
        DiagnosticEmitter eventDiagnostics = DiagnosticEmitter.of(
                DiagnosticsSettings.disabled().withListener(event -> {
                    events.add(event);
                    if (event.type() == DiagnosticEventType.PROCESS_EXITED) {
                        diagnosticBarrier.countDown();
                    }
                }),
                "listen",
                CommandEcho.empty());
        BoundedCloseDispatcher closeDispatcher = new BoundedCloseDispatcher(3, 3, 6, (name, task) -> {
            if (name.startsWith("procwright-process-stdin-close-")) {
                awaitUninterruptibly(stdout.readStarted);
                awaitUninterruptibly(stderr.readStarted);
                throw constructionFailure;
            }
            return Threading.start(name, task);
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
                                ZeroReadBackoff.exponential(),
                                trackingStarter)));

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
        eventDiagnostics.emit(DiagnosticEventType.PROCESS_EXITED, DiagnosticEmitter.attributes("timedOut", "false"));
        assertTrue(diagnosticBarrier.await(2, TimeUnit.SECONDS));
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
    void cleanupFailureIsSuppressedOnTheConstructionFailure() {
        AssertionError constructionFailure = new AssertionError("stream construction failed");
        AssertionError cleanupFailure = new AssertionError("stream cleanup failed");

        StreamRuntime.closePreserving(
                () -> {
                    throw cleanupFailure;
                },
                constructionFailure);

        assertArrayEquals(new Throwable[] {cleanupFailure}, constructionFailure.getSuppressed());
    }

    @Test
    void outputReadFailureHasStableReason() throws Exception {
        IOException readFailure = new IOException("read failed");
        ControllableProcess process =
                new ControllableProcess(new FailingInputStream(readFailure), InputStream.nullInputStream(), null);
        DefaultSession rawSession = session(process);
        StreamSession stream = new DefaultStreamSession(rawSession, plan(), diagnostics());
        try {
            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
            StreamException streamFailure = assertInstanceOf(StreamException.class, failure.getCause());

            assertEquals(StreamException.Reason.OUTPUT_READ_FAILED, streamFailure.reason());
            assertSame(readFailure, streamFailure.getCause());
        } finally {
            stream.close();
        }
    }

    @Test
    void decoderInitializationFailureHasStableReasonForEitherPump() throws Exception {
        for (String source : List.of("stdout", "stderr")) {
            IllegalArgumentException cause = new IllegalArgumentException(source + " decoder initialization failed");
            Charset charset = new ThreadSelectedNewDecoderFailureCharset(source, cause);
            ControllableProcess process = new ControllableProcess();
            DefaultSession rawSession = session(process);
            StreamSession stream = new DefaultStreamSession(rawSession, plan(charset, 16), diagnostics());
            try {
                ExecutionException failure = assertThrows(
                        ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
                StreamException streamFailure = assertInstanceOf(StreamException.class, failure.getCause());

                assertEquals(StreamException.Reason.OUTPUT_READ_FAILED, streamFailure.reason());
                assertSame(cause, streamFailure.getCause());
                assertEquals(true, streamFailure.diagnostics().text().length() <= 16);
                assertFalse(process.isAlive());
            } finally {
                stream.close();
            }
        }
    }

    @Test
    void decoderRuntimeFailureHasStableReasonAndCleansUp() throws Exception {
        IllegalArgumentException cause = new IllegalArgumentException("stream decoder failed");
        Charset charset = new RuntimeFailureCharset(cause);
        ControllableProcess process =
                new ControllableProcess(new ByteArrayInputStream(new byte[] {1}), InputStream.nullInputStream(), null);
        DefaultSession rawSession = session(process);
        StreamSession stream = new DefaultStreamSession(rawSession, plan(charset, 16), diagnostics());
        try {
            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
            StreamException streamFailure = assertInstanceOf(StreamException.class, failure.getCause());

            assertEquals(StreamException.Reason.OUTPUT_READ_FAILED, streamFailure.reason());
            assertEquals(true, causeChainContains(streamFailure, cause));
            assertEquals(true, streamFailure.diagnostics().text().length() <= 16);
            assertFalse(process.isAlive());
        } finally {
            stream.close();
        }
    }

    @Test
    void outputDecoderErrorIsTerminalByIdentityAndPreventsLaterListenerCallsForEitherPump() throws Exception {
        for (String fatalSource : List.of("stdout", "stderr")) {
            AssertionError fatalError = new AssertionError("fatal " + fatalSource + " decoder failure");
            Charset charset = new ThreadSelectedFatalDecoderCharset(fatalSource, fatalError);
            GatedByteInputStream gatedOther = new GatedByteInputStream((byte) 'x');
            CloseTrackingInputStream fatalInput = new CloseTrackingInputStream(new byte[] {1});
            InputStream stdout = fatalSource.equals("stdout") ? fatalInput : gatedOther;
            InputStream stderr = fatalSource.equals("stderr") ? fatalInput : gatedOther;
            ControllableProcess process = new ControllableProcess(stdout, stderr, null);
            AtomicInteger listenerCalls = new AtomicInteger();
            DefaultSession rawSession = session(process);
            StreamSession stream = new DefaultStreamSession(
                    rawSession, plan(charset, 16, chunk -> listenerCalls.incrementAndGet()), diagnostics());
            try {
                assertTrue(process.awaitDestroyed());
                gatedOther.release();

                ExecutionException failure = assertThrows(
                        ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
                assertSame(fatalError, failure.getCause());
                assertEquals(0, listenerCalls.get(), "no listener invocation may begin after terminal selection");
                assertFalse(process.isAlive());
            } finally {
                gatedOther.release();
                stream.close();
            }
        }
    }

    @Test
    void typedOutputFailureWinsWhenItOccursBeforeFatalError() throws Exception {
        assertTypedAndFatalOutputFailuresAreArbitrated(true);
    }

    @Test
    void fatalErrorWinsWhenItOccursBeforeTypedOutputFailure() throws Exception {
        assertTypedAndFatalOutputFailuresAreArbitrated(false);
    }

    @Test
    void outputOnlyDecoderIsBoundedForEitherPump() throws Exception {
        for (String failingSource : List.of("stdout", "stderr")) {
            Charset charset = new ThreadSelectedOutputOnlyCharset(failingSource);
            CloseTrackingInputStream failing = new CloseTrackingInputStream(new byte[] {1});
            BlockingUntilClosedInputStream other = new BlockingUntilClosedInputStream();
            InputStream stdout = failingSource.equals("stdout") ? failing : other;
            InputStream stderr = failingSource.equals("stderr") ? failing : other;
            ControllableProcess process = new ControllableProcess(stdout, stderr, null);
            AtomicInteger listenerCalls = new AtomicInteger();
            DefaultSession rawSession = session(process);
            StreamSession stream = new DefaultStreamSession(
                    rawSession, plan(charset, 16, chunk -> listenerCalls.incrementAndGet()), diagnostics());
            try {
                ExecutionException failure = assertThrows(
                        ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
                StreamException streamFailure = assertInstanceOf(StreamException.class, failure.getCause());

                assertEquals(StreamException.Reason.OUTPUT_READ_FAILED, streamFailure.reason());
                assertInstanceOf(IncrementalTextDecoder.DecoderStateException.class, streamFailure.getCause());
                assertTrue(streamFailure.diagnostics().text().length() <= 16);
                assertEquals(0, listenerCalls.get(), "transactional decoding must not publish hostile staged output");
                assertTrue(failing.awaitClose());
                assertEquals(1, failing.closeCalls());
                assertEquals(1, other.closeCalls());
                assertFalse(process.isAlive());
            } finally {
                stream.close();
            }
        }
    }

    @Test
    void zeroLengthPumpsBackOffAndStopAfterCloseForEitherStream() throws Exception {
        for (boolean zeroStdout : List.of(true, false)) {
            ZeroForeverInputStream zeroStream = new ZeroForeverInputStream();
            CloseTrackingInputStream eofStream = new CloseTrackingInputStream(new byte[0]);
            BlockingZeroReadBackoff backoff = new BlockingZeroReadBackoff();
            InputStream stdout = zeroStdout ? zeroStream : eofStream;
            InputStream stderr = zeroStdout ? eofStream : zeroStream;
            ControllableProcess process = new ControllableProcess(stdout, stderr, null);
            DefaultSession rawSession = session(process);
            StreamSession stream =
                    new DefaultStreamSession(rawSession, plan(), diagnostics(), backoff, PumpStarter.threading());
            try {
                assertTrue(backoff.awaitEntered());
                assertEquals(1, zeroStream.reads());

                stream.close();
                backoff.release();
                stream.onExit().get(1, TimeUnit.SECONDS);

                Thread readerThread = zeroStream.readerThread();
                readerThread.join(TimeUnit.SECONDS.toMillis(1));
                assertFalse(readerThread.isAlive());
                assertEquals(1, zeroStream.reads());
                assertTrue(zeroStream.awaitClose());
                assertTrue(eofStream.awaitClose());
                assertEquals(1, zeroStream.closeCalls());
                assertEquals(1, eofStream.closeCalls());
                assertFalse(process.isAlive());
            } finally {
                backoff.release();
                stream.close();
            }
        }
    }

    @Test
    void closeStopsProcessBeforeBlockingOutputClosesAndDoesNotWaitForThem() throws Exception {
        AtomicBoolean processAlive = new AtomicBoolean(true);
        BlockingCloseInputStream stdout = new BlockingCloseInputStream(processAlive);
        BlockingCloseInputStream stderr = new BlockingCloseInputStream(processAlive);
        ControllableProcess process =
                new ControllableProcess(stdout, stderr, null, OutputStream.nullOutputStream(), processAlive);
        DefaultSession rawSession = session(process);
        StreamSession stream = new DefaultStreamSession(rawSession, plan(), diagnostics());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Future<?> close = null;
        try {
            assertTrue(stdout.awaitReadStarted());
            assertTrue(stderr.awaitReadStarted());

            close = executor.submit(stream::close);

            assertTrue(process.awaitDestroyed(), "process cleanup must precede helper output closure");
            close.get(1, TimeUnit.SECONDS);
            assertTrue(rawSession.exitCompleted());
            assertFalse(rawSession.onExit().isDone());
            assertTrue(stdout.awaitCloseStarted());
            assertTrue(stderr.awaitCloseStarted());
            assertTrue(stdout.destroyedBeforeClose());
            assertTrue(stderr.destroyedBeforeClose());
            assertFalse(stdout.closeCompleted());
            assertFalse(stderr.closeCompleted());
        } finally {
            stdout.releaseClose();
            stderr.releaseClose();
            if (close != null) {
                close.get(1, TimeUnit.SECONDS);
            }
            stream.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }

        assertTrue(stdout.awaitCloseCompleted());
        assertTrue(stderr.awaitCloseCompleted());
        rawSession.onExit().get(1, TimeUnit.SECONDS);
        stream.onExit().get(1, TimeUnit.SECONDS);
        assertEquals(1, stdout.closeCalls());
        assertEquals(1, stderr.closeCalls());
    }

    @Test
    void ordinaryCloseWinsAndReportsLaterFatalAndPhysicalCloseFailuresByIdentity() throws Exception {
        AssertionError fatalFailure = new AssertionError("fatal pump failure after close");
        AssertionError stdoutCloseFailure = new AssertionError("stdout close failed");
        AssertionError stderrCloseFailure = new AssertionError("stderr close failed");
        GatedFailureInputStream fatalReads = new GatedFailureInputStream(fatalFailure);
        CloseFailingInputStream stdout = new CloseFailingInputStream(fatalReads, stdoutCloseFailure);
        CloseFailingInputStream stderr = new CloseFailingInputStream(InputStream.nullInputStream(), stderrCloseFailure);
        ControllableProcess process = new ControllableProcess(stdout, stderr, null);
        DefaultSession rawSession = session(process);
        CopyOnWriteArrayList<Throwable> reported = new CopyOnWriteArrayList<>();
        CountDownLatch reports = new CountDownLatch(3);
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            reported.add(failure);
            reports.countDown();
        });
        StreamSession stream = new DefaultStreamSession(rawSession, plan(), diagnostics());
        try {
            assertTrue(fatalReads.awaitReadEntered());

            stream.close();
            assertTrue(stdout.awaitClose());
            assertTrue(stderr.awaitClose());

            fatalReads.release();
            assertTrue(fatalReads.awaitThrow());
            var exit = stream.onExit().get(2, TimeUnit.SECONDS);

            assertTrue(exit.closed());
            assertFalse(exit.timedOut());
            assertTrue(reports.await(1, TimeUnit.SECONDS));
            assertEquals(
                    1,
                    reported.stream().filter(failure -> failure == fatalFailure).count());
            assertEquals(
                    1,
                    reported.stream()
                            .filter(failure -> failure == stdoutCloseFailure)
                            .count());
            assertEquals(
                    1,
                    reported.stream()
                            .filter(failure -> failure == stderrCloseFailure)
                            .count());
            assertEquals(0, fatalFailure.getSuppressed().length);
            assertEquals(1, stdout.closeCalls());
            assertEquals(1, stderr.closeCalls());
        } finally {
            fatalReads.release();
            stream.close();
            rawSession.close();
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Test
    void processFailureHasStableReason() throws Exception {
        IllegalStateException processFailure = new IllegalStateException("wait failed");
        ControllableProcess process =
                new ControllableProcess(InputStream.nullInputStream(), InputStream.nullInputStream(), processFailure);
        DefaultSession rawSession = session(process);
        StreamSession stream = new DefaultStreamSession(rawSession, plan(), diagnostics());
        try {
            assertTrue(process.awaitWaitFailure());
            process.releaseWaitFailure();
            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
            StreamException streamFailure = assertInstanceOf(StreamException.class, failure.getCause());

            assertEquals(StreamException.Reason.PROCESS_FAILED, streamFailure.reason());
            assertSame(processFailure, streamFailure.getCause());
        } finally {
            process.releaseWaitFailure();
            stream.close();
        }
    }

    @Test
    void processWaitErrorIsTerminalByIdentity() throws Exception {
        AssertionError processFailure = new AssertionError("fatal wait failure");
        ControllableProcess process =
                new ControllableProcess(InputStream.nullInputStream(), InputStream.nullInputStream(), processFailure);
        DefaultSession rawSession = session(process);
        StreamSession stream = new DefaultStreamSession(rawSession, plan(), diagnostics());
        AtomicReference<Throwable> reportedFailure = new AtomicReference<>();
        CountDownLatch uncaughtReported = new CountDownLatch(1);
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((ignored, failure) -> {
            reportedFailure.compareAndSet(null, failure);
            uncaughtReported.countDown();
        });
        try {
            assertTrue(process.awaitWaitFailure());
            process.releaseWaitFailure();

            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
            assertSame(processFailure, failure.getCause());
            assertTrue(uncaughtReported.await(1, TimeUnit.SECONDS));
            assertSame(processFailure, reportedFailure.get());
            assertFalse(process.isAlive());
        } finally {
            process.releaseWaitFailure();
            stream.close();
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Test
    void hostileCompletionCauseAccessorStillFailStopsAndCompletesStreamExit() throws Exception {
        AssertionError causeAccessFailure = new AssertionError("hostile getCause");
        HostileCompletionException processFailure = new HostileCompletionException(causeAccessFailure);
        ControllableProcess process =
                new ControllableProcess(InputStream.nullInputStream(), InputStream.nullInputStream(), processFailure);
        DefaultSession rawSession = session(process);
        StreamSession stream = new DefaultStreamSession(rawSession, plan(), diagnostics());
        try {
            assertTrue(process.awaitWaitFailure());
            process.releaseWaitFailure();

            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
            StreamException streamFailure = assertInstanceOf(StreamException.class, failure.getCause());
            assertEquals(StreamException.Reason.PROCESS_FAILED, streamFailure.reason());
            assertSame(processFailure, streamFailure.getCause());
            assertFalse(process.isAlive());
        } finally {
            process.releaseWaitFailure();
            stream.close();
        }
    }

    private static DefaultSession session(Process process) {
        return new DefaultSession(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                diagnostics());
    }

    private static StreamExecutionPlan plan() {
        return plan(StandardCharsets.UTF_8, 1024);
    }

    private static StreamExecutionPlan plan(Charset charset, int diagnosticLimit) {
        return plan(charset, diagnosticLimit, chunk -> {});
    }

    private static StreamExecutionPlan plan(Charset charset, int diagnosticLimit, StreamListener listener) {
        LaunchPlan launchPlan = new LaunchPlan(
                LaunchMode.DIRECT,
                List.of("stub"),
                Optional.empty(),
                EnvironmentPolicy.INHERIT,
                Map.of(),
                OutputMode.SEPARATE,
                TerminalPolicy.DISABLED);
        SessionExecutionPlan sessionPlan = new SessionExecutionPlan(
                launchPlan,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                Duration.ZERO,
                charset,
                PtyProvider.unavailable(),
                TerminalSize.defaults());
        return new StreamExecutionPlan(
                sessionPlan, Duration.ZERO, diagnosticLimit, listener, DiagnosticsSettings.disabled());
    }

    private static void assertTypedAndFatalOutputFailuresAreArbitrated(boolean typedFirst) throws Exception {
        IOException readFailure = new IOException("controlled stdout read failure");
        AssertionError fatalError = new AssertionError("controlled stderr fatal failure");
        GatedFailureInputStream typedStream = new GatedFailureInputStream(readFailure);
        GatedFailureInputStream fatalStream = new GatedFailureInputStream(fatalError);
        ControllableProcess process = new ControllableProcess(typedStream, fatalStream, null);
        DefaultSession rawSession = session(process);
        StreamSession stream = new DefaultStreamSession(rawSession, plan(), diagnostics());
        try {
            assertTrue(typedStream.awaitReadEntered());
            assertTrue(fatalStream.awaitReadEntered());
            if (typedFirst) {
                typedStream.release();
                assertTrue(typedStream.awaitThrow());
            } else {
                fatalStream.release();
                assertTrue(fatalStream.awaitThrow());
            }
            assertTrue(process.awaitDestroyed(), "the first failure must complete fail-stop cleanup");

            if (typedFirst) {
                fatalStream.release();
                assertTrue(fatalStream.awaitThrow());
            } else {
                typedStream.release();
                assertTrue(typedStream.awaitThrow());
            }

            ExecutionException failure =
                    assertThrows(ExecutionException.class, () -> stream.onExit().get(2, TimeUnit.SECONDS));
            if (typedFirst) {
                StreamException primary = assertInstanceOf(StreamException.class, failure.getCause());
                assertEquals(StreamException.Reason.OUTPUT_READ_FAILED, primary.reason());
                assertSame(readFailure, primary.getCause());
                assertSuppressedOnce(primary, fatalError);
            } else {
                assertSame(fatalError, failure.getCause());
                StreamException typedFailure = assertInstanceOf(StreamException.class, singleSuppressed(fatalError));
                assertEquals(StreamException.Reason.OUTPUT_READ_FAILED, typedFailure.reason());
                assertSame(readFailure, typedFailure.getCause());
            }
        } finally {
            typedStream.release();
            fatalStream.release();
            stream.close();
        }
    }

    private static Throwable singleSuppressed(Throwable primary) {
        assertEquals(1, primary.getSuppressed().length);
        return primary.getSuppressed()[0];
    }

    private static void assertSuppressedOnce(Throwable primary, Throwable expected) {
        int count = 0;
        for (Throwable suppressed : primary.getSuppressed()) {
            if (suppressed == expected) {
                count++;
            }
        }
        assertEquals(1, count);
    }

    private static DiagnosticEmitter diagnostics() {
        return DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "stream-runtime-test", CommandEcho.empty());
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
                LaunchMode.DIRECT,
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

    private static boolean causeChainContains(Throwable failure, Throwable expected) {
        Throwable current = failure;
        while (current != null) {
            if (current == expected) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static final class ControllableProcess extends Process {

        private final CompletableFuture<Integer> exit = new CompletableFuture<>();
        private final AtomicBoolean alive;
        private final CountDownLatch destroyed = new CountDownLatch(1);
        private final CountDownLatch waitEntered = new CountDownLatch(1);
        private final CountDownLatch waitFailureRelease = new CountDownLatch(1);
        private final InputStream stdout;
        private final InputStream stderr;
        private final Throwable waitFailure;
        private final OutputStream stdin;

        private ControllableProcess() {
            this(InputStream.nullInputStream(), InputStream.nullInputStream(), null);
        }

        private ControllableProcess(InputStream stdout, InputStream stderr, Throwable waitFailure) {
            this(stdout, stderr, waitFailure, OutputStream.nullOutputStream());
        }

        private ControllableProcess(InputStream stdout, InputStream stderr, Throwable waitFailure, OutputStream stdin) {
            this(stdout, stderr, waitFailure, stdin, new AtomicBoolean(true));
        }

        private ControllableProcess(
                InputStream stdout,
                InputStream stderr,
                Throwable waitFailure,
                OutputStream stdin,
                AtomicBoolean alive) {
            this.stdout = stdout;
            this.stderr = stderr;
            this.waitFailure = waitFailure;
            this.stdin = stdin;
            this.alive = alive;
        }

        @Override
        public OutputStream getOutputStream() {
            return stdin;
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
            throwWaitFailure();
            try {
                return exit.get();
            } catch (ExecutionException exception) {
                throw new IllegalStateException(exception.getCause());
            }
        }

        @Override
        public boolean waitFor(long timeout, TimeUnit unit) throws InterruptedException {
            throwWaitFailure();
            try {
                exit.get(timeout, unit);
                return true;
            } catch (TimeoutException exception) {
                return false;
            } catch (ExecutionException exception) {
                throw new IllegalStateException(exception.getCause());
            }
        }

        @Override
        public int exitValue() {
            Integer exitCode = exit.getNow(null);
            if (exitCode == null) {
                throw new IllegalThreadStateException("process is alive");
            }
            return exitCode;
        }

        @Override
        public void destroy() {
            alive.set(false);
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
            return alive.get();
        }

        @Override
        public Stream<ProcessHandle> descendants() {
            return Stream.empty();
        }

        private boolean awaitDestroyed() throws InterruptedException {
            return destroyed.await(1, TimeUnit.SECONDS);
        }

        private boolean awaitWaitFailure() throws InterruptedException {
            return waitEntered.await(1, TimeUnit.SECONDS);
        }

        private void releaseWaitFailure() {
            waitFailureRelease.countDown();
        }

        private void throwWaitFailure() throws InterruptedException {
            if (waitFailure == null) {
                return;
            }
            waitEntered.countDown();
            waitFailureRelease.await();
            if (waitFailure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw (Error) waitFailure;
        }
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

    private static final class HostileCompletionException extends CompletionException {

        private static final long serialVersionUID = 1L;

        private final AssertionError causeAccessFailure;

        private HostileCompletionException(AssertionError causeAccessFailure) {
            super("hostile completion", null);
            this.causeAccessFailure = causeAccessFailure;
        }

        @Override
        public synchronized Throwable getCause() {
            throw causeAccessFailure;
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

    private static final class FailingInputStream extends InputStream {

        private final IOException failure;

        private FailingInputStream(IOException failure) {
            this.failure = failure;
        }

        @Override
        public int read() throws IOException {
            throw failure;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            throw failure;
        }
    }

    private static final class GatedFailureInputStream extends InputStream {

        private final Throwable failure;
        private final CountDownLatch readEntered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch beforeThrow = new CountDownLatch(1);

        private GatedFailureInputStream(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public int read() throws IOException {
            readEntered.countDown();
            awaitUninterruptibly(release);
            beforeThrow.countDown();
            if (failure instanceof IOException exception) {
                throw exception;
            }
            throw (Error) failure;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return read();
        }

        @Override
        public void close() {
            // The test releases failures explicitly to prove first-occurrence arbitration.
        }

        private void release() {
            release.countDown();
        }

        private boolean awaitReadEntered() throws InterruptedException {
            return readEntered.await(1, TimeUnit.SECONDS);
        }

        private boolean awaitThrow() throws InterruptedException {
            return beforeThrow.await(1, TimeUnit.SECONDS);
        }
    }

    private static final class CloseFailingInputStream extends InputStream {

        private final InputStream reads;
        private final Error closeFailure;
        private final AtomicInteger closes = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);

        private CloseFailingInputStream(InputStream reads, Error closeFailure) {
            this.reads = reads;
            this.closeFailure = closeFailure;
        }

        @Override
        public int read() throws IOException {
            return reads.read();
        }

        @Override
        public int read(byte[] buffer, int offset, int length) throws IOException {
            return reads.read(buffer, offset, length);
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            closed.countDown();
            throw closeFailure;
        }

        private boolean awaitClose() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }

        private int closeCalls() {
            return closes.get();
        }
    }

    private static final class GatedByteInputStream extends InputStream {

        private final byte value;
        private final CountDownLatch release = new CountDownLatch(1);
        private final AtomicBoolean delivered = new AtomicBoolean();

        private GatedByteInputStream(byte value) {
            this.value = value;
        }

        @Override
        public int read() {
            awaitUninterruptibly(release);
            return delivered.compareAndSet(false, true) ? Byte.toUnsignedInt(value) : -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (length == 0) {
                return 0;
            }
            int next = read();
            if (next < 0) {
                return -1;
            }
            buffer[offset] = (byte) next;
            return 1;
        }

        @Override
        public void close() {
            // The test releases the byte after terminal selection.
        }

        private void release() {
            release.countDown();
        }
    }

    private static final class CloseTrackingInputStream extends InputStream {

        private final byte[] bytes;
        private final AtomicInteger closes = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);
        private int index;

        private CloseTrackingInputStream(byte[] bytes) {
            this.bytes = bytes.clone();
        }

        @Override
        public int read() {
            return index < bytes.length ? Byte.toUnsignedInt(bytes[index++]) : -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (length == 0) {
                return 0;
            }
            int remaining = bytes.length - index;
            if (remaining == 0) {
                return -1;
            }
            int count = Math.min(length, remaining);
            System.arraycopy(bytes, index, buffer, offset, count);
            index += count;
            return count;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            closed.countDown();
        }

        private int closeCalls() {
            return closes.get();
        }

        private boolean awaitClose() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }
    }

    private static final class BlockingUntilClosedInputStream extends InputStream {

        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicInteger closes = new AtomicInteger();

        @Override
        public int read() {
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
            closed.countDown();
        }

        private int closeCalls() {
            return closes.get();
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

    private static final class BlockingCloseInputStream extends InputStream {

        private final AtomicBoolean processAlive;
        private final CountDownLatch readStarted = new CountDownLatch(1);
        private final CountDownLatch closeStarted = new CountDownLatch(1);
        private final CountDownLatch closeRelease = new CountDownLatch(1);
        private final CountDownLatch closeCompleted = new CountDownLatch(1);
        private final AtomicBoolean destroyedBeforeClose = new AtomicBoolean();
        private final AtomicInteger closes = new AtomicInteger();

        private BlockingCloseInputStream(AtomicBoolean processAlive) {
            this.processAlive = processAlive;
        }

        @Override
        public int read() {
            readStarted.countDown();
            awaitUninterruptibly(closeCompleted);
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
            closeStarted.countDown();
            awaitUninterruptibly(closeRelease);
            closeCompleted.countDown();
        }

        private boolean awaitReadStarted() throws InterruptedException {
            return readStarted.await(1, TimeUnit.SECONDS);
        }

        private boolean awaitCloseStarted() throws InterruptedException {
            return closeStarted.await(1, TimeUnit.SECONDS);
        }

        private boolean awaitCloseCompleted() throws InterruptedException {
            return closeCompleted.await(1, TimeUnit.SECONDS);
        }

        private void releaseClose() {
            closeRelease.countDown();
        }

        private boolean destroyedBeforeClose() {
            return destroyedBeforeClose.get();
        }

        private boolean closeCompleted() {
            return closeCompleted.getCount() == 0;
        }

        private int closeCalls() {
            return closes.get();
        }
    }

    private static final class ZeroForeverInputStream extends InputStream {

        private final AtomicInteger reads = new AtomicInteger();
        private final AtomicInteger closes = new AtomicInteger();
        private final CountDownLatch closed = new CountDownLatch(1);
        private volatile Thread readerThread;

        @Override
        public int read() {
            recordRead();
            return 0;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            recordRead();
            return 0;
        }

        @Override
        public void close() {
            closes.incrementAndGet();
            closed.countDown();
        }

        private void recordRead() {
            readerThread = Thread.currentThread();
            reads.incrementAndGet();
        }

        private int reads() {
            return reads.get();
        }

        private int closeCalls() {
            return closes.get();
        }

        private boolean awaitClose() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }

        private Thread readerThread() {
            return readerThread;
        }
    }

    private static final class BlockingZeroReadBackoff implements ZeroReadBackoff {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);

        @Override
        public boolean pause(int consecutiveZeroReads, java.util.function.BooleanSupplier closed) {
            entered.countDown();
            awaitUninterruptibly(release);
            return !closed.getAsBoolean();
        }

        private boolean awaitEntered() throws InterruptedException {
            return entered.await(1, TimeUnit.SECONDS);
        }

        private void release() {
            release.countDown();
        }
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

    private static final class ThreadSelectedNewDecoderFailureCharset extends Charset {

        private final String failingThreadFragment;
        private final RuntimeException failure;

        private ThreadSelectedNewDecoderFailureCharset(String failingThreadFragment, RuntimeException failure) {
            super("X-Procwright-Stream-New-Decoder-Failure-" + failingThreadFragment, new String[0]);
            this.failingThreadFragment = failingThreadFragment;
            this.failure = failure;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            if (Thread.currentThread().getName().contains(failingThreadFragment)) {
                throw failure;
            }
            return passthroughDecoder(this);
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class RuntimeFailureCharset extends Charset {

        private final RuntimeException failure;

        private RuntimeFailureCharset(RuntimeException failure) {
            super("X-Procwright-Stream-Runtime-Failure", new String[0]);
            this.failure = failure;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (input.hasRemaining()) {
                        throw failure;
                    }
                    return CoderResult.UNDERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class ThreadSelectedFatalDecoderCharset extends Charset {

        private final String failingThreadFragment;
        private final AssertionError failure;

        private ThreadSelectedFatalDecoderCharset(String failingThreadFragment, AssertionError failure) {
            super("X-Procwright-Stream-Fatal-Decoder-" + failingThreadFragment, new String[0]);
            this.failingThreadFragment = failingThreadFragment;
            this.failure = failure;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            if (!Thread.currentThread().getName().contains(failingThreadFragment)) {
                return passthroughDecoder(this);
            }
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (input.hasRemaining()) {
                        input.get();
                        throw failure;
                    }
                    return CoderResult.UNDERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class ThreadSelectedOutputOnlyCharset extends Charset {

        private final String failingThreadFragment;

        private ThreadSelectedOutputOnlyCharset(String failingThreadFragment) {
            super("X-Procwright-Stream-Output-Only-" + failingThreadFragment, new String[0]);
            this.failingThreadFragment = failingThreadFragment;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            if (!Thread.currentThread().getName().contains(failingThreadFragment)) {
                return passthroughDecoder(this);
            }
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    while (output.hasRemaining()) {
                        output.put('x');
                    }
                    return CoderResult.OVERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static CharsetDecoder passthroughDecoder(Charset charset) {
        return new CharsetDecoder(charset, 1, 1) {
            @Override
            protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                while (input.hasRemaining() && output.hasRemaining()) {
                    output.put((char) Byte.toUnsignedInt(input.get()));
                }
                return input.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
            }
        };
    }
}
