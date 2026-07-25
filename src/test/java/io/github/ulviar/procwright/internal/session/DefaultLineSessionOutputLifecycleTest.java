/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.BoundedFailureReporterTestSupport;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSessionException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class DefaultLineSessionOutputLifecycleTest extends DefaultLineSessionOutputLifecycleTestSupport {

    @Test
    void blockingPublicExitContinuationObservesReleasedOutputCleanupOwners() throws Exception {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 2, 4);
        ControllableProcess process = new ControllableProcess(
                OutputStream.nullOutputStream(), InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultLineSession lineSession =
                new DefaultLineSession(session(process, dispatcher), LineSessionSettings.defaults());
        CountDownLatch continuationEntered = new CountDownLatch(1);
        CountDownLatch releaseContinuation = new CountDownLatch(1);
        AtomicBoolean cleanupWasComplete = new AtomicBoolean();
        CompletableFuture<Void> continuation = lineSession.onExit().thenRun(() -> {
            cleanupWasComplete.set(lineSession.physicalOutputCleanup().isDone()
                    && dispatcher.activeCount() == 0
                    && dispatcher.pendingCount() == 0
                    && dispatcher.outstandingCount() == 0);
            continuationEntered.countDown();
            awaitUninterruptibly(releaseContinuation);
        });
        FutureTask<Void> closeTask = new FutureTask<>(() -> {
            lineSession.close();
            return null;
        });
        Thread closeThread = new Thread(closeTask, "procwright-line-blocking-continuation-test");
        closeThread.setDaemon(true);
        closeThread.start();
        try {
            assertTrue(continuationEntered.await(1, TimeUnit.SECONDS));
            assertTrue(cleanupWasComplete.get());
            BoundedCloseDispatcher.Reservation fullCapacity = dispatcher.reserve(4);
            fullCapacity.release();
            assertFalse(continuation.isDone());
        } finally {
            releaseContinuation.countDown();
        }
        closeTask.get(1, TimeUnit.SECONDS);
        continuation.get(1, TimeUnit.SECONDS);
        assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
    }

    @Test
    void publicExitWaitsForFallbackOwnedPhysicalOutputClose() throws Exception {
        IllegalStateException startFailure = new IllegalStateException("line stdout close starter failed");
        IOException physicalFailure = new IOException("line stdout physical close failed");
        FailingBlockingPhysicalCloseInputStream stdout = new FailingBlockingPhysicalCloseInputStream(physicalFailure);
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 1, 3, (name, task) -> {
            if (name.contains("stdout-close")) {
                throw startFailure;
            }
            io.github.ulviar.procwright.internal.Threading.start(name, task);
        });
        DefaultLineSession lineSession =
                new DefaultLineSession(session(process, dispatcher), LineSessionSettings.defaults());
        AtomicInteger startReports = new AtomicInteger();
        AtomicInteger physicalReports = new AtomicInteger();
        AtomicReference<Throwable> unexpectedReport = new AtomicReference<>();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            if (failure == startFailure) {
                startReports.incrementAndGet();
            } else if (failure == physicalFailure) {
                physicalReports.incrementAndGet();
            } else {
                unexpectedReport.compareAndSet(null, failure);
            }
        });
        try {
            process.complete(0);
            assertTrue(stdout.closeEntered.await(1, TimeUnit.SECONDS));

            assertFalse(lineSession.physicalOutputCleanup().isDone());
            assertFalse(lineSession.onExit().isDone(), "public helper exit must wait for fallback output settlement");

            stdout.releaseClose.countDown();
            lineSession.onExit().handle((result, failure) -> null).get(1, TimeUnit.SECONDS);
            assertTrue(lineSession.physicalOutputCleanup().isDone());
            assertTrue(BoundedFailureReporterTestSupport.awaitSharedSettlement(Duration.ofSeconds(1)));
            assertEquals(1, stdout.closeCalls.get());
            assertEquals(1, startReports.get());
            assertEquals(1, physicalReports.get());
            assertEquals(null, unexpectedReport.get());
            assertEquals(0, startFailure.getSuppressed().length);
            assertEquals(0, physicalFailure.getSuppressed().length);
            assertTrue(eventually(() -> dispatcher.activeCount() == 0
                    && dispatcher.pendingCount() == 0
                    && dispatcher.outstandingCount() == 0));
        } finally {
            stdout.releaseClose.countDown();
            process.complete(143);
            try {
                lineSession.close();
            } catch (RuntimeException | Error expectedTerminalFailure) {
                assertSame(startFailure, expectedTerminalFailure);
            }
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Test
    void fallbackHelperTerminalContinuationCannotStrandAnotherLineSessionTerminal() throws Exception {
        BoundedCloseDispatcher dispatcher = outputStartFailingDispatcher(6);
        ControllableProcess firstProcess = new ControllableProcess(
                OutputStream.nullOutputStream(), InputStream.nullInputStream(), InputStream.nullInputStream());
        ControllableProcess secondProcess = new ControllableProcess(
                OutputStream.nullOutputStream(), InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultLineSession first =
                new DefaultLineSession(session(firstProcess, dispatcher), LineSessionSettings.defaults());
        DefaultLineSession second =
                new DefaultLineSession(session(secondProcess, dispatcher), LineSessionSettings.defaults());
        CompletableFuture<Void> escape = new CompletableFuture<>();
        CompletableFuture<Void> secondTerminal = second.onExit().handle((ignored, failure) -> null);
        CountDownLatch firstContinuationEntered = new CountDownLatch(1);
        CompletableFuture<Void> firstContinuation = first.onExit().handle((ignored, failure) -> {
            firstContinuationEntered.countDown();
            CompletableFuture.anyOf(secondTerminal, escape).join();
            return null;
        });
        try {
            firstProcess.complete(0);
            assertTrue(firstContinuationEntered.await(1, TimeUnit.SECONDS));

            secondProcess.complete(0);

            secondTerminal.get(1, TimeUnit.SECONDS);
            firstContinuation.get(1, TimeUnit.SECONDS);
            assertEquals(0, dispatcher.outstandingCount());
        } finally {
            escape.complete(null);
            firstProcess.complete(143);
            secondProcess.complete(143);
            closeIgnoringTerminal(first);
            closeIgnoringTerminal(second);
        }
    }

    @Test
    void fatalOutputDecoderErrorFailStopsActiveAndFollowUpRequestsForEitherStream() throws Exception {
        for (boolean fatalStdout : List.of(true, false)) {
            AssertionError fatalError =
                    new AssertionError("fatal " + (fatalStdout ? "stdout" : "stderr") + " decoder failure");
            LatchingFatalDecoderCharset charset = new LatchingFatalDecoderCharset(fatalError);
            GatedByteInputStream fatalStream = new GatedByteInputStream((byte) '!');
            BlockingUntilClosedInputStream blockedStdout = new BlockingUntilClosedInputStream();
            InputStream stdout = fatalStdout ? fatalStream : blockedStdout;
            InputStream stderr = fatalStdout ? InputStream.nullInputStream() : fatalStream;
            CountingOutputStream stdin = new CountingOutputStream();
            ControllableProcess process = new ControllableProcess(stdin, stdout, stderr);
            DefaultSession rawSession = session(process);
            DefaultLineSession lineSession =
                    new DefaultLineSession(rawSession, options(charset).withTranscriptLimit(32));
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Throwable> request = executor.submit(() -> captureFailure(() -> lineSession.requestEncoded(
                        "request\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(2))));
                assertTrue(stdin.awaitWrite(), "the request must be active before output decoding fails");
                fatalStream.releaseByte();
                assertTrue(charset.awaitBeforeFailure());
                charset.releaseFailure();

                assertSame(fatalError, request.get(2, TimeUnit.SECONDS));
                lineSession.onExit().get(1, TimeUnit.SECONDS);
                assertFalse(process.isAlive());
                assertTrue(lineSession.transcript().text().length() <= 32);
                int writesAfterFailure = stdin.writeCalls();

                AssertionError followUp = assertThrows(
                        AssertionError.class,
                        () -> lineSession.requestEncoded(
                                "retry\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));
                assertSame(fatalError, followUp);
                assertEquals(writesAfterFailure, stdin.writeCalls());
            } finally {
                fatalStream.releaseByte();
                charset.releaseFailure();
                try {
                    lineSession.close();
                } finally {
                    blockedStdout.close();
                    executor.shutdownNow();
                    assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
                }
            }
        }
    }

    @Test
    void userCloseWinningBeforePumpErrorStillRegistersTheErrorForPhysicalCloseFailures() throws Exception {
        AssertionError pumpError = new AssertionError("late line pump failure");
        AssertionError stdoutCloseFailure = new AssertionError("stdout close failed");
        AssertionError stderrCloseFailure = new AssertionError("stderr close failed");
        ControlledPumpFailureInputStream stdout = new ControlledPumpFailureInputStream(pumpError, stdoutCloseFailure);
        ControlledPumpFailureInputStream stderr = new ControlledPumpFailureInputStream(null, stderrCloseFailure);
        ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream(), stdout, stderr);
        DefaultSession rawSession = session(process);
        List<Thread> pumpThreads = new ArrayList<>();
        AtomicReference<Throwable> uncaughtPumpFailure = new AtomicReference<>();
        PumpStarter starter = (name, task) -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, failure) -> uncaughtPumpFailure.compareAndSet(null, failure));
            pumpThreads.add(thread);
            thread.start();
            return thread;
        };
        DefaultLineSession lineSession = new DefaultLineSession(
                rawSession, LineSessionSettings.defaults(), LineSessionTestDependencies.withPumpStarter(starter));
        try {
            assertTrue(stdout.awaitReadEntered());

            lineSession.close();
            assertTrue(stdout.awaitCloseEntered());
            assertTrue(stderr.awaitCloseEntered());

            stdout.releaseReadFailure();
            for (Thread pumpThread : pumpThreads) {
                pumpThread.join(TimeUnit.SECONDS.toMillis(1));
                assertFalse(pumpThread.isAlive());
            }
            assertEquals(null, uncaughtPumpFailure.get());

            stdout.releaseCloseFailure();
            stderr.releaseCloseFailure();
            assertTrue(stdout.awaitCloseWorkerStopped());
            assertTrue(stderr.awaitCloseWorkerStopped());
            ExecutionException cleanupFailure = assertThrows(
                    ExecutionException.class,
                    () -> rawSession.physicalOutputCleanup().get(1, TimeUnit.SECONDS));
            assertSame(stdoutCloseFailure, cleanupFailure.getCause().getCause());
            assertEquals(
                    List.of(stderrCloseFailure),
                    List.of(cleanupFailure.getCause().getSuppressed()));
            assertEquals(0, stdoutCloseFailure.getSuppressed().length);
            assertEquals(0, stderrCloseFailure.getSuppressed().length);

            assertEquals(0, pumpError.getSuppressed().length);
        } finally {
            stdout.releaseReadFailure();
            stdout.releaseCloseFailure();
            stderr.releaseCloseFailure();
            lineSession.close();
            rawSession.close();
        }
    }

    @Test
    void activeEofFailureOwnsCloseFailuresThatFinishBeforeRequestArbitration() throws Exception {
        AssertionError stdoutCloseFailure = new AssertionError("stdout close failed");
        AssertionError stderrCloseFailure = new AssertionError("stderr close failed");
        GatedEofCloseFailureInputStream stdout = new GatedEofCloseFailureInputStream(stdoutCloseFailure);
        GatedEofCloseFailureInputStream stderr = new GatedEofCloseFailureInputStream(stderrCloseFailure);
        ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream(), stdout, stderr);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 2, 4);
        DefaultSession rawSession = session(process, dispatcher);
        CountDownLatch decoderEntered = new CountDownLatch(1);
        CountDownLatch releaseDecoder = new CountDownLatch(1);
        LineSessionSettings settings = LineSessionSettings.defaults().withResponseDecoder(reader -> {
            decoderEntered.countDown();
            awaitUninterruptibly(releaseDecoder);
            return List.of(reader.readLine());
        });
        DefaultLineSession lineSession = new DefaultLineSession(rawSession, settings);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicInteger lateReports = new AtomicInteger();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            if (failure == stdoutCloseFailure || failure == stderrCloseFailure) {
                lateReports.incrementAndGet();
            }
        });
        try {
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> lineSession.request("request")));
            assertTrue(decoderEntered.await(1, TimeUnit.SECONDS));

            stdout.releaseEof();
            stderr.releaseEof();
            process.complete(0);
            assertTrue(stdout.awaitClose());
            assertTrue(stderr.awaitClose());
            assertThrows(
                    ExecutionException.class,
                    () -> rawSession.physicalOutputCleanup().get(1, TimeUnit.SECONDS));
            assertFalse(request.isDone(), "request arbitration must remain delayed");
            assertFalse(lineSession.onExit().isDone(), "helper exit must retain failure attribution");

            releaseDecoder.countDown();
            LineSessionException eof = assertInstanceOf(LineSessionException.class, request.get(1, TimeUnit.SECONDS));
            assertEquals(LineSessionException.Reason.EOF, eof.reason());
            assertEquals(0, eof.getSuppressed().length);
            lineSession.onExit().handle((ignored, exitFailure) -> null).get(1, TimeUnit.SECONDS);
            assertTrue(BoundedFailureReporterTestSupport.awaitSharedSettlement(Duration.ofSeconds(1)));
            assertEquals(2, lateReports.get());
            assertEquals(0, dispatcher.outstandingCount());
        } finally {
            releaseDecoder.countDown();
            stdout.releaseEof();
            stderr.releaseEof();
            lineSession.close();
            rawSession.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Test
    void bufferedResponseSucceedsBeforeEofClosesFailureAttribution() throws Exception {
        AssertionError stdoutCloseFailure = new AssertionError("stdout close failed");
        AssertionError stderrCloseFailure = new AssertionError("stderr close failed");
        GatedEofCloseFailureInputStream stdout =
                new GatedEofCloseFailureInputStream("response\n".getBytes(StandardCharsets.UTF_8), stdoutCloseFailure);
        GatedEofCloseFailureInputStream stderr = new GatedEofCloseFailureInputStream(stderrCloseFailure);
        ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream(), stdout, stderr);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 2, 4);
        DefaultSession rawSession = session(process, dispatcher);
        CountDownLatch decoderEntered = new CountDownLatch(1);
        CountDownLatch releaseDecoder = new CountDownLatch(1);
        LineSessionSettings settings = LineSessionSettings.defaults().withResponseDecoder(reader -> {
            decoderEntered.countDown();
            awaitUninterruptibly(releaseDecoder);
            return List.of(reader.readLine());
        });
        DefaultLineSession lineSession = new DefaultLineSession(rawSession, settings);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicInteger lateReports = new AtomicInteger();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            if (failure == stdoutCloseFailure || failure == stderrCloseFailure) {
                lateReports.incrementAndGet();
            }
        });
        try {
            Future<LineResponse> request = executor.submit(() -> lineSession.request("request"));
            assertTrue(decoderEntered.await(1, TimeUnit.SECONDS));

            stdout.releaseEof();
            stderr.releaseEof();
            process.complete(0);
            assertTrue(stdout.awaitClose());
            assertTrue(stderr.awaitClose());
            assertThrows(
                    ExecutionException.class,
                    () -> rawSession.physicalOutputCleanup().get(1, TimeUnit.SECONDS));
            assertFalse(request.isDone(), "buffered response arbitration must remain delayed");
            assertFalse(lineSession.onExit().isDone(), "helper exit must retain failure attribution");

            releaseDecoder.countDown();
            assertEquals(List.of("response"), request.get(1, TimeUnit.SECONDS).lines());
            lineSession.onExit().handle((ignored, exitFailure) -> null).get(1, TimeUnit.SECONDS);
            assertTrue(BoundedFailureReporterTestSupport.awaitSharedSettlement(Duration.ofSeconds(1)));
            assertEquals(2, lateReports.get());
            assertEquals(0, dispatcher.outstandingCount());
        } finally {
            releaseDecoder.countDown();
            stdout.releaseEof();
            stderr.releaseEof();
            lineSession.close();
            rawSession.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Test
    void fatalStderrDecoderFailureReplacesAnEarlierResponseLimit() throws Exception {
        assertResponseLimitAndFatalErrorAreArbitrated();
    }

    @Test
    void overflowPrefixIsNeverPublishedWhenSameDecodeCallEndsMalformed() throws Exception {
        OverflowThenMalformedCharset charset = new OverflowThenMalformedCharset();
        ControllableProcess process = new ControllableProcess(
                OutputStream.nullOutputStream(),
                new ByteArrayInputStream(new byte[] {'x', 'y'}),
                InputStream.nullInputStream());
        DefaultSession rawSession = session(process);
        try (DefaultLineSession lineSession = new DefaultLineSession(
                rawSession, options(charset), LineSessionTestDependencies.withBackoff(ZeroReadBackoff.exponential()))) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<LineResponse> request = executor.submit(() -> lineSession.requestEncoded(
                        "request\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));
                assertTrue(charset.awaitBeforeMalformed());
                charset.releaseMalformed();

                ExecutionException requestFailure =
                        assertThrows(ExecutionException.class, () -> request.get(1, TimeUnit.SECONDS));
                LineSessionException failure = assertInstanceOf(LineSessionException.class, requestFailure.getCause());

                assertEquals(LineSessionException.Reason.DECODE_ERROR, failure.reason());
                assertFalse(failure.transcript().text().contains("ok"));
                lineSession.onExit().get(1, TimeUnit.SECONDS);
                assertFalse(process.isAlive());
            } finally {
                charset.releaseMalformed();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void zeroLengthPumpsBackOffAndStopAfterCloseForEitherStream() throws Exception {
        for (boolean zeroStdout : List.of(true, false)) {
            ZeroForeverInputStream zeroStream = new ZeroForeverInputStream();
            BlockingZeroReadBackoff backoff = new BlockingZeroReadBackoff();
            InputStream stdout = zeroStdout ? zeroStream : InputStream.nullInputStream();
            InputStream stderr = zeroStdout ? InputStream.nullInputStream() : zeroStream;
            ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream(), stdout, stderr);
            DefaultSession rawSession = session(process);
            DefaultLineSession lineSession = new DefaultLineSession(
                    rawSession, LineSessionSettings.defaults(), LineSessionTestDependencies.withBackoff(backoff));
            try {
                assertTrue(backoff.awaitEntered());
                assertEquals(1, zeroStream.reads(), "the pump must enter backoff before attempting another read");

                try {
                    lineSession.close();
                } finally {
                    backoff.release();
                }
                lineSession.onExit().get(1, TimeUnit.SECONDS);
                Thread readerThread = zeroStream.readerThread();
                readerThread.join(TimeUnit.SECONDS.toMillis(1));
                assertFalse(readerThread.isAlive(), "line pump thread must terminate after close");
                assertEquals(1, zeroStream.reads(), "close during backoff must prevent another read");
                assertFalse(process.isAlive());
            } finally {
                try {
                    backoff.release();
                } finally {
                    lineSession.close();
                }
            }
        }
    }

    @Test
    void interruptedZeroLengthPumpRestoresInterruptStatusBeforeStopping() throws Exception {
        ZeroForeverInputStream stdout = new ZeroForeverInputStream();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        DefaultSession rawSession = session(process);
        try (DefaultLineSession lineSession = new DefaultLineSession(rawSession, LineSessionSettings.defaults())) {
            assertTrue(stdout.awaitFirstRead());
            Thread readerThread = stdout.readerThread();

            readerThread.interrupt();
            readerThread.join(TimeUnit.SECONDS.toMillis(1));

            assertFalse(readerThread.isAlive(), "interrupted line pump thread must terminate");
            assertTrue(readerThread.isInterrupted(), "line pump must restore its interrupted status");
            assertFalse(
                    lineSession.transcript().malformed(),
                    "interrupting zero-read backoff must not fabricate malformed output");
            LineSessionException failure =
                    assertThrows(LineSessionException.class, () -> lineSession.request("request"));
            assertEquals(LineSessionException.Reason.FAILURE, failure.reason());
            assertTrue(failure.getMessage().contains("stdout output pump failed"));
            assertTrue(failure.getCause() instanceof CommandExecutionException);
            lineSession.onExit().handle((ignored, exitFailure) -> null).get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
        }
    }

    @Test
    void stderrDecodeFailureIdentifiesDiagnosticStream() throws Exception {
        InputStream stdout = new BlockingUntilClosedInputStream();
        InputStream stderr = new ByteArrayInputStream(new byte[] {(byte) 0xC3});
        ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream(), stdout, stderr);
        DefaultSession rawSession = session(process);
        try (DefaultLineSession lineSession = new DefaultLineSession(rawSession, options(StandardCharsets.UTF_8))) {
            lineSession.onExit().handle((ignored, failure) -> null).get(1, TimeUnit.SECONDS);

            LineSessionException failure =
                    assertThrows(LineSessionException.class, () -> lineSession.request("request"));
            assertEquals(LineSessionException.Reason.DECODE_ERROR, failure.reason());
            assertTrue(failure.getMessage().contains("Could not decode line-session stderr"));
            assertTrue(failure.transcript().malformed());
        }
    }

    private static void assertResponseLimitAndFatalErrorAreArbitrated() throws Exception {
        AssertionError fatalError = new AssertionError("fatal stderr decoder failure");
        RacingLineDecoderCharset charset = new RacingLineDecoderCharset(fatalError);
        GatedByteInputStream stdout = new GatedByteInputStream((byte) 'x');
        GatedByteInputStream stderr = new GatedByteInputStream((byte) '!');
        CountingOutputStream stdin = new CountingOutputStream();
        AtomicReference<LineSessionException> observedResponseFailure = new AtomicReference<>();
        CountDownLatch responseFailureCaught = new CountDownLatch(1);
        CountDownLatch allowCallbackReturn = new CountDownLatch(1);
        LineSessionSettings options = options(charset).withMaxResponseChars(1).withResponseDecoder(reader -> {
            try {
                reader.readLine();
                throw new AssertionError("response limit was not enforced");
            } catch (LineSessionException failure) {
                observedResponseFailure.set(failure);
                responseFailureCaught.countDown();
                awaitUninterruptibly(allowCallbackReturn);
                return List.of("fallback");
            }
        });
        ControllableProcess process = new ControllableProcess(stdin, stdout, stderr);
        DefaultSession rawSession = session(process);
        DefaultLineSession lineSession = new DefaultLineSession(rawSession, options);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = executor.submit(() -> captureFailure(() ->
                    lineSession.requestEncoded("request\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(5))));
            assertTrue(stdin.awaitWrite(), "the request must be active before either output failure");

            stdout.releaseByte();
            assertTrue(charset.awaitResponseDecoder(), "stdout decoder did not reach its controlled boundary");
            charset.releaseResponseDecoder();
            assertTrue(
                    responseFailureCaught.await(1, TimeUnit.SECONDS),
                    "response limit must occupy the terminal outcome before stderr fails");

            stderr.releaseByte();
            assertTrue(charset.awaitFatalDecoder(), "stderr decoder did not reach its controlled boundary");
            charset.releaseFatalDecoder();

            lineSession.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
            allowCallbackReturn.countDown();

            Throwable thrown = request.get(2, TimeUnit.SECONDS);
            LineSessionException responseFailure = observedResponseFailure.get();
            assertEquals(LineSessionException.Reason.RESPONSE_TOO_LARGE, responseFailure.reason());
            assertSame(fatalError, thrown);
            assertEquals(0, fatalError.getSuppressed().length);
            assertEquals(0, responseFailure.getSuppressed().length);

            int writesAfterFailure = stdin.writeCalls();
            Throwable followUp = captureFailure(() ->
                    lineSession.requestEncoded("retry\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));
            assertSame(fatalError, followUp);
            assertEquals(writesAfterFailure, stdin.writeCalls());
        } finally {
            stdout.releaseByte();
            stderr.releaseByte();
            charset.releaseResponseDecoder();
            charset.releaseFatalDecoder();
            allowCallbackReturn.countDown();
            try {
                lineSession.close();
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }
}
