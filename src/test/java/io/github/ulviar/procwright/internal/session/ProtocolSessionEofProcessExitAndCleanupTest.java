/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.diagnostics.CommandEcho;
import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.DiagnosticEmitter;
import io.github.ulviar.procwright.internal.DiagnosticsSettings;
import io.github.ulviar.procwright.internal.ProcessTreeScannerTestSupport;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ProtocolSessionEofProcessExitAndCleanupTest extends ProtocolSessionContractSupport {

    @Test
    void publicExitDoesNotWaitForPhysicalOutputCleanup() throws Exception {
        BlockingPhysicalCloseInputStream stdout = new BlockingPhysicalCloseInputStream();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        DefaultProtocolSession<String, String> protocol =
                protocolSession(process, noOpAdapter(), ProtocolSessionSettings.defaults());
        try {
            process.exitNaturally(0);
            assertTrue(stdout.closeEntered.await(1, TimeUnit.SECONDS));

            assertEquals(
                    0, protocol.onExit().get(1, TimeUnit.SECONDS).exitCode().orElseThrow());

            stdout.releaseClose.countDown();
            assertTrue(stdout.closeFinished.await(1, TimeUnit.SECONDS));
        } finally {
            stdout.releaseClose.countDown();
            process.exitNaturally(143);
            protocol.close();
        }
    }

    @Test
    void blockingPublicExitContinuationDoesNotRetainPhysicalCloseCapacity() throws Exception {
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 2);
        ControllableProcess process = new ControllableProcess(
                OutputStream.nullOutputStream(), InputStream.nullInputStream(), InputStream.nullInputStream());
        DefaultProtocolSession<String, String> protocol =
                protocolSession(process, noOpAdapter(), ProtocolSessionSettings.defaults(), dispatcher);
        CountDownLatch continuationEntered = new CountDownLatch(1);
        CountDownLatch releaseContinuation = new CountDownLatch(1);
        CompletableFuture<Void> continuation = protocol.onExit().thenRun(() -> {
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
            assertTrue(eventually(() -> dispatcher.outstandingCount() == 0));
            assertFalse(continuation.isDone());
        } finally {
            releaseContinuation.countDown();
        }
        closeTask.get(1, TimeUnit.SECONDS);
        continuation.get(1, TimeUnit.SECONDS);
        assertEquals(0, dispatcher.outstandingCount());
    }

    @Test
    void pumpStartupFailureRacingProcessExitLeavesNoIncompleteSessionCleanup() throws Exception {
        ControllableProcess process = new ControllableProcess();
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
            Future<Throwable> construction = executor.submit(() -> captureFailure(() -> protocolSession(
                    process,
                    noOpAdapter(),
                    ProtocolSessionSettings.defaults(),
                    ProtocolSessionTestDependencies.withPumpStarter(starter))));
            assertTrue(starterEntered.await(1, TimeUnit.SECONDS));

            process.exitNaturally(29);
            releaseStarter.countDown();

            assertSame(startupFailure, construction.get(2, TimeUnit.SECONDS));
            assertFalse(process.isAlive());
        } finally {
            releaseStarter.countDown();
            process.exitNaturally(143);
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
        DefaultProtocolSession<String, Byte> protocol =
                protocolSession(process, byteReadingAdapter(responseReadStarted), ProtocolSessionSettings.defaults());
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
        CountDownLatch responseReadStarted = new CountDownLatch(1);
        DefaultProtocolSession<String, Byte> protocol = protocolSession(
                process,
                byteReadingAdapter(responseReadStarted),
                ProtocolSessionSettings.defaults(),
                DefaultProtocolSession.Dependencies.defaults(),
                DiagnosticEmitter.of(DiagnosticsSettings.disabled(), "protocol-eof-test", CommandEcho.empty()),
                new BoundedCloseDispatcher(2, 2),
                watcherStarter);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> protocol.request("request")));
            assertTrue(responseReadStarted.await(1, TimeUnit.SECONDS));
            assertTrue(stdout.awaitReadEntered());

            process.exitNaturally(17);
            assertFalse(protocol.onExit().isDone(), "the exit observer must still be withheld");
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
    void naturalExitCodeRemainsAvailableWhileOutputCleanupIsPending() throws Exception {
        GatedEofInputStream stdout = new GatedEofInputStream();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        CountDownLatch responseReadStarted = new CountDownLatch(1);
        AtomicReference<DefaultSession> session = new AtomicReference<>();
        DefaultProtocolSession<String, Byte> protocol = protocolSession(
                process, byteReadingAdapter(responseReadStarted), ProtocolSessionSettings.defaults(), session::set);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> protocol.request("request")));
            assertTrue(responseReadStarted.await(1, TimeUnit.SECONDS));
            assertTrue(stdout.awaitReadEntered());

            process.exitNaturally(17);
            assertTrue(eventually(() -> session.get().processExitCode().orElse(-1) == 17));
            assertFalse(protocol.onExit().isDone());

            stdout.releaseEof();
            ProtocolSessionException processExited =
                    assertInstanceOf(ProtocolSessionException.class, request.get(2, TimeUnit.SECONDS));

            assertEquals(ProtocolSessionException.Reason.PROCESS_EXITED, processExited.reason());
            assertEquals(17, processExited.exitCode().orElseThrow());
        } finally {
            stdout.releaseEof();
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
                protocolSession(guarded, readOnlyAdapter, ProtocolSessionSettings.defaults());
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

    private static boolean eventually(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        do {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(5);
        } while (System.nanoTime() < deadline);
        return condition.getAsBoolean();
    }
}
