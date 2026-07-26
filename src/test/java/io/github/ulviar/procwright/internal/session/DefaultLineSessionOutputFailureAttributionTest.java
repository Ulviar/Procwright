/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.ControllableProcess;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.awaitUninterruptibly;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.captureFailure;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.openSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedCloseDispatcher;
import io.github.ulviar.procwright.internal.BoundedFailureReporterTestSupport;
import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSessionException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class DefaultLineSessionOutputFailureAttributionTest {

    @Test
    void userCloseWinningBeforePumpErrorStillRegistersTheErrorForPhysicalCloseFailures() throws Exception {
        AssertionError pumpError = new AssertionError("late line pump failure");
        AssertionError stdoutCloseFailure = new AssertionError("stdout close failed");
        AssertionError stderrCloseFailure = new AssertionError("stderr close failed");
        ControlledPumpFailureInputStream stdout = new ControlledPumpFailureInputStream(pumpError, stdoutCloseFailure);
        ControlledPumpFailureInputStream stderr = new ControlledPumpFailureInputStream(null, stderrCloseFailure);
        ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream(), stdout, stderr);
        DefaultSession rawSession = openSession(process);
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
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 2);
        DefaultSession rawSession = openSession(process, dispatcher);
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
        BoundedCloseDispatcher dispatcher = new BoundedCloseDispatcher(2, 2);
        DefaultSession rawSession = openSession(process, dispatcher);
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

    private static final class ControlledPumpFailureInputStream extends InputStream {

        final Error readFailure;
        final Error closeFailure;
        final CountDownLatch readEntered = new CountDownLatch(1);
        final CountDownLatch releaseRead = new CountDownLatch(1);
        final CountDownLatch closeEntered = new CountDownLatch(1);
        final CountDownLatch releaseClose = new CountDownLatch(1);
        volatile Thread closeThread;

        ControlledPumpFailureInputStream(Error readFailure, Error closeFailure) {
            this.readFailure = readFailure;
            this.closeFailure = closeFailure;
        }

        @Override
        public int read() {
            if (readFailure == null) {
                return -1;
            }
            readEntered.countDown();
            awaitUninterruptibly(releaseRead);
            throw readFailure;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            return length == 0 ? 0 : read();
        }

        @Override
        public void close() {
            closeThread = Thread.currentThread();
            closeEntered.countDown();
            awaitUninterruptibly(releaseClose);
            throw closeFailure;
        }

        boolean awaitReadEntered() throws InterruptedException {
            return readEntered.await(1, TimeUnit.SECONDS);
        }

        void releaseReadFailure() {
            releaseRead.countDown();
        }

        boolean awaitCloseEntered() throws InterruptedException {
            return closeEntered.await(1, TimeUnit.SECONDS);
        }

        void releaseCloseFailure() {
            releaseClose.countDown();
        }

        boolean awaitCloseWorkerStopped() throws InterruptedException {
            Thread worker = closeThread;
            if (worker == null) {
                return false;
            }
            worker.join(TimeUnit.SECONDS.toMillis(1));
            return !worker.isAlive();
        }
    }

    private static final class GatedEofCloseFailureInputStream extends InputStream {

        final AssertionError closeFailure;
        final byte[] payload;
        final CountDownLatch releaseEof = new CountDownLatch(1);
        final CountDownLatch closed = new CountDownLatch(1);
        int offset;

        GatedEofCloseFailureInputStream(AssertionError closeFailure) {
            this(new byte[0], closeFailure);
        }

        GatedEofCloseFailureInputStream(byte[] payload, AssertionError closeFailure) {
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

        void releaseEof() {
            releaseEof.countDown();
        }

        boolean awaitClose() throws InterruptedException {
            return closed.await(1, TimeUnit.SECONDS);
        }
    }
}
