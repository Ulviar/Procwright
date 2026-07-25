/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.BoundedFailureReporterTestSupport;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticEmitterTestSupport;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.ProcessTreeScannerTestSupport;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
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

final class ProtocolSessionEofProcessExitAndCleanupTest extends ProtocolSessionContractSupport {

    @Test
    void publicExitWaitsForPhysicalOutputCleanup() throws Exception {
        BlockingPhysicalCloseInputStream stdout = new BlockingPhysicalCloseInputStream();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        DefaultProtocolSession<String, String> protocol =
                new DefaultProtocolSession<>(session(process), noOpAdapter(), ProtocolSessionSettings.defaults());
        try {
            process.exitNaturally(0);
            assertTrue(stdout.closeEntered.await(1, TimeUnit.SECONDS));

            assertFalse(protocol.physicalOutputCleanup().isDone());
            assertFalse(protocol.onExit().isDone(), "public protocol exit must wait for physical output cleanup");

            stdout.releaseClose.countDown();
            assertEquals(
                    0, protocol.onExit().get(1, TimeUnit.SECONDS).exitCode().orElseThrow());
            assertTrue(protocol.physicalOutputCleanup().isDone());
        } finally {
            stdout.releaseClose.countDown();
            process.exitNaturally(143);
            protocol.close();
        }
    }

    @Test
    void activeEofFailureOwnsCloseFailuresThatFinishBeforeRequestArbitration() throws Exception {
        AssertionError stdoutCloseFailure = new AssertionError("stdout close failed");
        AssertionError stderrCloseFailure = new AssertionError("stderr close failed");
        GatedEofCloseFailureInputStream stdout = new GatedEofCloseFailureInputStream(stdoutCloseFailure);
        GatedEofCloseFailureInputStream stderr = new GatedEofCloseFailureInputStream(stderrCloseFailure);
        ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream(), stdout, stderr);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 2);
        DefaultSession rawSession = session(process, dispatcher);
        CountDownLatch decoderEntered = new CountDownLatch(1);
        CountDownLatch releaseDecoder = new CountDownLatch(1);
        ProtocolAdapter<String, Byte> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public Byte readResponse(ProtocolReaders readers) {
                decoderEntered.countDown();
                awaitUninterruptibly(releaseDecoder);
                return readers.stdout().readByte();
            }
        };
        DefaultProtocolSession<String, Byte> protocol =
                new DefaultProtocolSession<>(rawSession, adapter, ProtocolSessionSettings.defaults());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicInteger lateReports = new AtomicInteger();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            if (failure == stdoutCloseFailure || failure == stderrCloseFailure) {
                lateReports.incrementAndGet();
            }
        });
        try {
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> protocol.request("request")));
            assertTrue(decoderEntered.await(1, TimeUnit.SECONDS));

            stdout.releaseEof();
            stderr.releaseEof();
            process.exitNaturally(0);
            assertTrue(stdout.awaitClose());
            assertTrue(stderr.awaitClose());
            assertThrows(
                    ExecutionException.class,
                    () -> rawSession.physicalOutputCleanup().get(1, TimeUnit.SECONDS));
            assertFalse(request.isDone(), "request arbitration must remain delayed");
            assertFalse(protocol.onExit().isDone(), "helper exit must retain failure attribution");

            releaseDecoder.countDown();
            ProtocolSessionException eof =
                    assertInstanceOf(ProtocolSessionException.class, request.get(1, TimeUnit.SECONDS));
            assertTrue(eof.reason() == ProtocolSessionException.Reason.EOF
                    || eof.reason() == ProtocolSessionException.Reason.PROCESS_EXITED);
            assertEquals(0, eof.getSuppressed().length);
            protocol.onExit().handle((ignored, failure) -> null).get(1, TimeUnit.SECONDS);
            assertTrue(BoundedFailureReporterTestSupport.awaitSharedSettlement(Duration.ofSeconds(1)));
            assertEquals(2, lateReports.get());
            assertEquals(0, dispatcher.outstandingCount());
        } finally {
            releaseDecoder.countDown();
            stdout.releaseEof();
            stderr.releaseEof();
            protocol.close();
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
                new GatedEofCloseFailureInputStream(new byte[] {42}, stdoutCloseFailure);
        GatedEofCloseFailureInputStream stderr = new GatedEofCloseFailureInputStream(stderrCloseFailure);
        ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream(), stdout, stderr);
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 2);
        DefaultSession rawSession = session(process, dispatcher);
        CountDownLatch decoderEntered = new CountDownLatch(1);
        CountDownLatch releaseDecoder = new CountDownLatch(1);
        ProtocolAdapter<String, Byte> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public Byte readResponse(ProtocolReaders readers) {
                decoderEntered.countDown();
                awaitUninterruptibly(releaseDecoder);
                return readers.stdout().readByte();
            }
        };
        DefaultProtocolSession<String, Byte> protocol =
                new DefaultProtocolSession<>(rawSession, adapter, ProtocolSessionSettings.defaults());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicInteger lateReports = new AtomicInteger();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, failure) -> {
            if (failure == stdoutCloseFailure || failure == stderrCloseFailure) {
                lateReports.incrementAndGet();
            }
        });
        try {
            Future<Byte> request = executor.submit(() -> protocol.request("request"));
            assertTrue(decoderEntered.await(1, TimeUnit.SECONDS));

            stdout.releaseEof();
            stderr.releaseEof();
            process.exitNaturally(0);
            assertTrue(stdout.awaitClose());
            assertTrue(stderr.awaitClose());
            assertThrows(
                    ExecutionException.class,
                    () -> rawSession.physicalOutputCleanup().get(1, TimeUnit.SECONDS));
            assertFalse(request.isDone(), "buffered response arbitration must remain delayed");
            assertFalse(protocol.onExit().isDone(), "helper exit must retain failure attribution");

            releaseDecoder.countDown();
            assertEquals((byte) 42, request.get(1, TimeUnit.SECONDS));
            protocol.onExit().handle((ignored, failure) -> null).get(1, TimeUnit.SECONDS);
            assertTrue(BoundedFailureReporterTestSupport.awaitSharedSettlement(Duration.ofSeconds(1)));
            assertEquals(2, lateReports.get());
            assertEquals(0, dispatcher.outstandingCount());
        } finally {
            releaseDecoder.countDown();
            stdout.releaseEof();
            stderr.releaseEof();
            protocol.close();
            rawSession.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            Thread.setDefaultUncaughtExceptionHandler(previous);
        }
    }

    @Test
    void blockingPublicExitContinuationObservesReleasedPhysicalCloseCapacity() throws Exception {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 2);
        ControllableProcess process = new ControllableProcess(
                OutputStream.nullOutputStream(), InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultProtocolSession<String, String> protocol = new DefaultProtocolSession<>(
                session(process, dispatcher), noOpAdapter(), ProtocolSessionSettings.defaults());
        CountDownLatch continuationEntered = new CountDownLatch(1);
        CountDownLatch releaseContinuation = new CountDownLatch(1);
        AtomicBoolean cleanupWasComplete = new AtomicBoolean();
        CompletableFuture<Void> continuation = protocol.onExit().thenRun(() -> {
            cleanupWasComplete.set(protocol.physicalOutputCleanup().isDone()
                    && dispatcher.activeCount() == 0
                    && dispatcher.pendingCount() == 0
                    && dispatcher.outstandingCount() == 0);
            continuationEntered.countDown();
            awaitUninterruptibly(releaseContinuation);
        });
        FutureTask<Void> closeTask = new FutureTask<>(() -> {
            protocol.close();
            return null;
        });
        Thread closeThread = new Thread(closeTask, "procwright-protocol-blocking-continuation-test");
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
    }

    @Test
    void pumpStartupFailureRacingProcessExitLeavesNoIncompleteSessionCleanup() throws Exception {
        ControllableProcess process = new ControllableProcess();
        DefaultSession rawSession = session(process);
        AssertionError startupFailure = new AssertionError("pump startup failed");
        CountDownLatch starterEntered = new CountDownLatch(1);
        CountDownLatch releaseStarter = new CountDownLatch(1);
        PumpStarter starter = (name, task) -> {
            starterEntered.countDown();
            awaitUninterruptibly(releaseStarter);
            throw startupFailure;
        };
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> construction = executor.submit(() -> captureFailure(() -> new DefaultProtocolSession<>(
                    rawSession,
                    noOpAdapter(),
                    ProtocolSessionSettings.defaults(),
                    ProtocolSessionTestDependencies.withPumpStarter(starter))));
            assertTrue(starterEntered.await(1, TimeUnit.SECONDS));

            process.exitNaturally(29);
            releaseStarter.countDown();

            assertSame(startupFailure, construction.get(2, TimeUnit.SECONDS));
            rawSession.onExit().get(2, TimeUnit.SECONDS);
        } finally {
            releaseStarter.countDown();
            process.exitNaturally(143);
            rawSession.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void stdoutEofWhileProcessIsAliveIsClassifiedAsEofWithoutGraceDelay() throws Exception {
        GatedEofInputStream stdout = new GatedEofInputStream();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        CountDownLatch responseReadStarted = new CountDownLatch(1);
        DefaultProtocolSession<String, Byte> protocol = new DefaultProtocolSession<>(
                session(process), byteReadingAdapter(responseReadStarted), ProtocolSessionSettings.defaults());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> protocol.request("request")));
            assertTrue(responseReadStarted.await(1, TimeUnit.SECONDS));
            assertTrue(stdout.awaitReadEntered());

            stdout.releaseEof();
            ProtocolSessionException eof =
                    assertInstanceOf(ProtocolSessionException.class, request.get(2, TimeUnit.SECONDS));

            assertEquals(ProtocolSessionException.Reason.EOF, eof.reason());
            assertTrue(eof.exitCode().isEmpty());
        } finally {
            stdout.releaseEof();
            protocol.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void processExitBeforeStdoutEofRemainsEofWhenCachedExitPublicationIsDelayed() throws Exception {
        GatedEofInputStream stdout = new GatedEofInputStream();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        CountDownLatch releaseExitWatcher = new CountDownLatch(1);
        AtomicReference<Thread> exitWatcher = new AtomicReference<>();
        DefaultSession.WatcherStarter watcherStarter = (name, task) -> {
            Thread thread = new Thread(
                    () -> {
                        awaitUninterruptibly(releaseExitWatcher);
                        task.run();
                    },
                    name);
            thread.setDaemon(true);
            exitWatcher.set(thread);
            thread.start();
            return thread;
        };
        DefaultSession rawSession = DefaultSession.openTransactionally(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "protocol-eof-test", CommandEcho.empty()),
                () -> {},
                new BoundedCloseDispatcher(2, 2),
                watcherStarter);
        CountDownLatch responseReadStarted = new CountDownLatch(1);
        DefaultProtocolSession<String, Byte> protocol = new DefaultProtocolSession<>(
                rawSession, byteReadingAdapter(responseReadStarted), ProtocolSessionSettings.defaults());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> protocol.request("request")));
            assertTrue(responseReadStarted.await(1, TimeUnit.SECONDS));
            assertTrue(stdout.awaitReadEntered());

            process.exitNaturally(17);
            assertFalse(rawSession.onExit().isDone(), "the exit observer must still be withheld");
            stdout.releaseEof();
            ProtocolSessionException eof =
                    assertInstanceOf(ProtocolSessionException.class, request.get(2, TimeUnit.SECONDS));

            assertEquals(ProtocolSessionException.Reason.EOF, eof.reason());
            assertTrue(eof.exitCode().isEmpty());
        } finally {
            stdout.releaseEof();
            releaseExitWatcher.countDown();
            protocol.close();
            Thread watcher = exitWatcher.get();
            if (watcher != null) {
                watcher.join(TimeUnit.SECONDS.toMillis(1));
                assertFalse(watcher.isAlive());
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void naturalExitCodeIsObservedBeforeOutputCleanupPublishesExit() throws Exception {
        GatedEofInputStream stdout = new GatedEofInputStream();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        CountDownLatch exitPublicationEntered = new CountDownLatch(1);
        CountDownLatch releaseExitPublication = new CountDownLatch(1);
        DiagnosticEmitter diagnostics = DiagnosticEmitterTestSupport.blockOnceOn(
                DiagnosticsSettings.disabled().withListener(ignored -> {}),
                "protocol-exit-observation-test",
                DiagnosticEventType.PROCESS_EXITED,
                exitPublicationEntered,
                releaseExitPublication);
        DefaultSession rawSession = DefaultSession.openTransactionally(
                process,
                Duration.ZERO,
                ShutdownPolicy.interruptThenKill(Duration.ZERO, Duration.ZERO),
                StandardCharsets.UTF_8,
                diagnostics,
                () -> {},
                new BoundedCloseDispatcher(2, 2),
                DefaultSession.WatcherStarter.threading());
        CountDownLatch responseReadStarted = new CountDownLatch(1);
        DefaultProtocolSession<String, Byte> protocol = new DefaultProtocolSession<>(
                rawSession, byteReadingAdapter(responseReadStarted), ProtocolSessionSettings.defaults());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> protocol.request("request")));
            assertTrue(responseReadStarted.await(1, TimeUnit.SECONDS));
            assertTrue(stdout.awaitReadEntered());

            process.exitNaturally(17);
            assertTrue(exitPublicationEntered.await(1, TimeUnit.SECONDS));
            assertFalse(rawSession.onExit().isDone());
            assertFalse(protocol.onExit().isDone());

            stdout.releaseEof();
            ProtocolSessionException processExited =
                    assertInstanceOf(ProtocolSessionException.class, request.get(2, TimeUnit.SECONDS));

            assertEquals(ProtocolSessionException.Reason.PROCESS_EXITED, processExited.reason());
            assertEquals(17, processExited.exitCode().orElseThrow());
        } finally {
            stdout.releaseEof();
            releaseExitPublication.countDown();
            protocol.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void scannerSaturationDuringEofEnrichmentRetainsEof() throws Exception {
        GatedEofInputStream stdout = new GatedEofInputStream();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        process.blockLivenessQueries();
        Process guarded = ProcessTreeScannerTestSupport.guard(process, 1, Duration.ofSeconds(2));
        ProtocolAdapter<String, Byte> readOnlyAdapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {}

            @Override
            public Byte readResponse(ProtocolReaders readers) {
                return readers.stdout().readByte();
            }
        };
        DefaultProtocolSession<String, Byte> protocol =
                new DefaultProtocolSession<>(session(guarded), readOnlyAdapter, ProtocolSessionSettings.defaults());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            assertTrue(process.awaitLivenessQuery(), "exit watcher did not saturate the scanner");
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> protocol.request("request")));

            stdout.releaseEof();
            ProtocolSessionException eof =
                    assertInstanceOf(ProtocolSessionException.class, request.get(2, TimeUnit.SECONDS));

            assertEquals(ProtocolSessionException.Reason.EOF, eof.reason());
            assertTrue(eof.exitCode().isEmpty());
            assertEquals(0, process.exitValueCalls());
        } finally {
            stdout.releaseEof();
            process.releaseLivenessQueries();
            process.exitNaturally(143);
            protocol.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private static ProtocolAdapter<String, Byte> byteReadingAdapter() {
        return byteReadingAdapter(new CountDownLatch(0));
    }

    private static ProtocolAdapter<String, Byte> byteReadingAdapter(CountDownLatch responseReadStarted) {
        return new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public Byte readResponse(ProtocolReaders readers) {
                responseReadStarted.countDown();
                return readers.stdout().readByte();
            }
        };
    }

    private static final class GatedEofCloseFailureInputStream extends InputStream {

        private final AssertionError closeFailure;
        private final byte[] payload;
        private final CountDownLatch releaseEof = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private int offset;

        private GatedEofCloseFailureInputStream(AssertionError closeFailure) {
            this(new byte[0], closeFailure);
        }

        private GatedEofCloseFailureInputStream(byte[] payload, AssertionError closeFailure) {
            this.payload = payload.clone();
            this.closeFailure = closeFailure;
        }

        @Override
        public int read() {
            awaitUninterruptibly(releaseEof);
            return offset < payload.length ? payload[offset++] & 0xFF : -1;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            awaitUninterruptibly(releaseEof);
            if (this.offset == payload.length) {
                return -1;
            }
            int count = Math.min(length, payload.length - this.offset);
            System.arraycopy(payload, this.offset, bytes, offset, count);
            this.offset += count;
            return count;
        }

        @Override
        public void close() {
            closed.countDown();
            throw closeFailure;
        }

        private void releaseEof() {
            releaseEof.countDown();
        }

        private boolean awaitClose() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }
    }
}
