/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ProtocolSessionOutputFailureTest extends ProtocolSessionContractSupport {

    @Test
    void idleStdoutIoFailureSettlesPublicExit() throws Exception {
        IOException readFailure = new IOException("stdout read failed");
        InputStream stdout = new InputStream() {
            @Override
            public int read() throws IOException {
                throw readFailure;
            }
        };
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        DefaultProtocolSession<String, String> protocol =
                protocolSession(process, noOpAdapter(), ProtocolSessionSettings.defaults());
        try {
            protocol.onExit().handle((ignored, failure) -> null).get(1, TimeUnit.SECONDS);
            ProtocolSessionException terminal =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("request"));

            assertSame(readFailure, terminal.getCause());
            assertFalse(process.isAlive());
        } finally {
            protocol.close();
        }
    }

    private static boolean eventually(java.util.function.BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (!condition.getAsBoolean()) {
            if (deadline - System.nanoTime() <= 0) {
                return false;
            }
            Thread.sleep(1);
        }
        return true;
    }

    @Test
    void latePumpErrorAfterCloseDoesNotChangeTheClosedOutcome() throws Exception {
        AssertionError pumpError = new AssertionError("late protocol pump failure");
        AssertionError stdoutCloseFailure = new AssertionError("stdout close failed");
        AssertionError stderrCloseFailure = new AssertionError("stderr close failed");
        ControlledPumpFailureInputStream stdout = new ControlledPumpFailureInputStream(pumpError, stdoutCloseFailure);
        ControlledPumpFailureInputStream stderr = new ControlledPumpFailureInputStream(null, stderrCloseFailure);
        ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream(), stdout, stderr);
        List<Thread> pumpThreads = new ArrayList<>();
        PumpStarter starter = (name, task) -> {
            Thread thread = new Thread(task, name);
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler((ignored, failure) -> {});
            pumpThreads.add(thread);
            thread.start();
            return thread;
        };
        DefaultProtocolSession<String, String> protocolSession = protocolSession(
                process,
                noOpAdapter(),
                ProtocolSessionSettings.defaults(),
                ProtocolSessionTestDependencies.withPumpStarter(starter));
        try {
            assertTrue(stdout.awaitReadEntered());

            protocolSession.close();
            assertTrue(stdout.awaitCloseEntered());
            assertTrue(stderr.awaitCloseEntered());

            stdout.releaseReadFailure();
            for (Thread pumpThread : pumpThreads) {
                pumpThread.join(TimeUnit.SECONDS.toMillis(1));
                assertFalse(pumpThread.isAlive());
            }

            stdout.releaseCloseFailure();
            stderr.releaseCloseFailure();
            assertTrue(stdout.awaitCloseWorkerStopped());
            assertTrue(stderr.awaitCloseWorkerStopped());

            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> protocolSession.request("after-close"));
            assertEquals(ProtocolSessionException.Reason.CLOSED, followUp.reason());
        } finally {
            stdout.releaseReadFailure();
            stdout.releaseCloseFailure();
            stderr.releaseCloseFailure();
            protocolSession.close();
        }
    }

    @Test
    void protocolPumpIgnoresZeroLengthReadBeforeRealByte() {
        ControllableProcess process = new ControllableProcess(
                OutputStream.nullOutputStream(), new ZeroThenByteInputStream((byte) 42), InputStream.nullInputStream());
        ProtocolAdapter<String, Byte> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public Byte readResponse(ProtocolReaders readers) {
                return readers.stdout().readByte();
            }
        };

        try (DefaultProtocolSession<String, Byte> protocol =
                protocolSession(process, adapter, ProtocolSessionSettings.defaults())) {
            assertEquals((byte) 42, protocol.request("ignored"));
        }
    }

    @Test
    void caughtRepeatedStderrOverflowReadsRetainOneFailureAndCannotReturnSuccess() {
        AtomicReference<ProtocolSessionException> firstObserved = new AtomicReference<>();
        AtomicInteger observedReads = new AtomicInteger();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                for (int attempt = 0; attempt < 10_000; attempt++) {
                    ProtocolSessionException observed = assertThrows(
                            ProtocolSessionException.class,
                            () -> readers.stderr().readByte());
                    ProtocolSessionException first =
                            firstObserved.updateAndGet(existing -> existing == null ? observed : existing);
                    assertSame(first, observed);
                    observedReads.incrementAndGet();
                }
                return "must-not-succeed";
            }
        };
        ControllableProcess process = new ControllableProcess(
                OutputStream.nullOutputStream(),
                InputStream.nullInputStream(),
                new java.io.ByteArrayInputStream(new byte[] {1, 2}));
        ProtocolSessionSettings settings = ProtocolSessionSettings.defaults().withOutputBacklogLimit(1);

        try (DefaultProtocolSession<String, String> protocol = protocolSession(process, adapter, settings)) {
            ProtocolSessionException requestFailure =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("request"));

            assertEquals(10_000, observedReads.get());
            assertSame(firstObserved.get(), requestFailure);
            assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, requestFailure.reason());
            assertEquals(0, requestFailure.getSuppressed().length);
            assertEquals(0, requestFailure.getCause().getSuppressed().length);
        }
    }

    @Test
    void stderrOverflowRemainsRequestLocalAfterProcessAndOutputHaveSettled() throws Exception {
        BlockingPhysicalCloseInputStream stdout = new BlockingPhysicalCloseInputStream();
        GatedChunkInputStream stderr = new GatedChunkInputStream(new byte[] {1, 2});
        ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream(), stdout, stderr);
        AtomicReference<DefaultSession> rawSession = new AtomicReference<>();
        CountDownLatch decoderEntered = new CountDownLatch(1);
        CountDownLatch allowRead = new CountDownLatch(1);
        ProtocolAdapter<String, Byte> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public Byte readResponse(ProtocolReaders readers) {
                decoderEntered.countDown();
                awaitUninterruptibly(allowRead);
                return readers.stderr().readByte();
            }
        };
        DefaultProtocolSession<String, Byte> protocol = protocolSession(
                process,
                adapter,
                ProtocolSessionSettings.defaults().withOutputBacklogLimit(1).withRequestTimeout(Duration.ofSeconds(5)),
                rawSession::set);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> protocol.request("request")));
            assertTrue(decoderEntered.await(1, TimeUnit.SECONDS));
            stderr.release();
            assertTrue(stderr.awaitEof(), "stderr pump did not retain the overflow marker");
            process.exitNaturally(23);
            assertTrue(eventually(() -> rawSession.get().processExitCode().isPresent()));
            assertTrue(stdout.closeEntered.await(1, TimeUnit.SECONDS));
            assertEquals(
                    23, protocol.onExit().get(1, TimeUnit.SECONDS).exitCode().orElseThrow());

            allowRead.countDown();
            ProtocolSessionException overflow =
                    assertInstanceOf(ProtocolSessionException.class, request.get(2, TimeUnit.SECONDS));

            assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, overflow.reason());
            assertEquals(23, overflow.exitCode().orElseThrow());
        } finally {
            allowRead.countDown();
            stderr.release();
            stdout.releaseClose.countDown();
            protocol.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void zeroForeverStdoutPumpStopsAfterRequestTimeout() throws Exception {
        ZeroForeverInputStream stdout = new ZeroForeverInputStream();
        BlockingZeroReadBackoff backoff = new BlockingZeroReadBackoff();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        ProtocolAdapter<String, Byte> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public Byte readResponse(ProtocolReaders readers) {
                return readers.stdout().readByte();
            }
        };

        DefaultProtocolSession<String, Byte> protocol = protocolSession(
                process,
                adapter,
                ProtocolSessionSettings.defaults().withRequestTimeout(Duration.ofMillis(50)),
                ProtocolSessionTestDependencies.withBackoff(backoff));
        try {
            assertTrue(backoff.awaitEntered());
            assertEquals(1, stdout.reads(), "the pump must enter backoff before attempting another read");

            ProtocolSessionException timeout;
            try {
                timeout = assertThrows(ProtocolSessionException.class, () -> protocol.request("ignored"));
            } finally {
                backoff.release();
            }

            assertEquals(ProtocolSessionException.Reason.TIMEOUT, timeout.reason());
            ExecutionException exitFailure = assertThrows(
                    ExecutionException.class, () -> protocol.onExit().get(1, TimeUnit.SECONDS));
            assertSame(timeout, exitFailure.getCause());
            assertFalse(process.isAlive());
            Thread readerThread = stdout.readerThread();
            readerThread.join(TimeUnit.SECONDS.toMillis(1));
            assertFalse(readerThread.isAlive(), "protocol pump thread must terminate after request timeout");
            assertEquals(1, stdout.reads(), "timeout during backoff must prevent another read");

            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("again"));
            assertEquals(ProtocolSessionException.Reason.TIMEOUT, followUp.reason());
        } finally {
            try {
                backoff.release();
            } finally {
                protocol.close();
            }
        }
    }

    @Test
    void interruptedZeroLengthPumpSettlesPublicExit() throws Exception {
        ZeroForeverInputStream stdout = new ZeroForeverInputStream();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        DefaultProtocolSession<String, String> protocol =
                protocolSession(process, noOpAdapter(), ProtocolSessionSettings.defaults());
        try {
            assertTrue(stdout.awaitFirstRead());
            Thread readerThread = stdout.readerThread();

            readerThread.interrupt();
            readerThread.join(TimeUnit.SECONDS.toMillis(1));

            assertFalse(readerThread.isAlive(), "interrupted protocol pump thread must terminate");
            assertTrue(readerThread.isInterrupted(), "protocol pump must restore its interrupted status");
            protocol.onExit().handle((ignored, failure) -> null).get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
        } finally {
            protocol.close();
        }
    }

    @Test
    void zeroForeverStderrPumpStopsAfterClose() throws Exception {
        ZeroForeverInputStream stderr = new ZeroForeverInputStream();
        BlockingZeroReadBackoff backoff = new BlockingZeroReadBackoff();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), InputStream.nullInputStream(), stderr);
        DefaultProtocolSession<String, String> protocol = protocolSession(
                process,
                noOpAdapter(),
                ProtocolSessionSettings.defaults(),
                ProtocolSessionTestDependencies.withBackoff(backoff));
        try {
            assertTrue(backoff.awaitEntered());
            assertEquals(1, stderr.reads(), "the pump must enter backoff before attempting another read");

            try {
                protocol.close();
            } finally {
                backoff.release();
            }

            protocol.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
            Thread readerThread = stderr.readerThread();
            readerThread.join(TimeUnit.SECONDS.toMillis(1));
            assertFalse(readerThread.isAlive(), "protocol pump thread must terminate after close");
            assertEquals(1, stderr.reads(), "close during backoff must prevent another read");
        } finally {
            try {
                backoff.release();
            } finally {
                protocol.close();
            }
        }
    }

    private static final class ControlledPumpFailureInputStream extends InputStream {

        private final Error readFailure;
        private final Error closeFailure;
        private final CountDownLatch readEntered = new CountDownLatch(1);
        private final CountDownLatch releaseRead = new CountDownLatch(1);
        private final CountDownLatch closeEntered = new CountDownLatch(1);
        private final CountDownLatch releaseClose = new CountDownLatch(1);
        private volatile Thread closeThread;

        private ControlledPumpFailureInputStream(Error readFailure, Error closeFailure) {
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

        private boolean awaitReadEntered() throws InterruptedException {
            return readEntered.await(1, TimeUnit.SECONDS);
        }

        private void releaseReadFailure() {
            releaseRead.countDown();
        }

        private boolean awaitCloseEntered() throws InterruptedException {
            return closeEntered.await(1, TimeUnit.SECONDS);
        }

        private void releaseCloseFailure() {
            releaseClose.countDown();
        }

        private boolean awaitCloseWorkerStopped() throws InterruptedException {
            Thread worker = closeThread;
            if (worker == null) {
                return false;
            }
            worker.join(TimeUnit.SECONDS.toMillis(1));
            return !worker.isAlive();
        }
    }

    private static final class GatedChunkInputStream extends InputStream {

        private final byte[] chunk;
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch eof = new CountDownLatch(1);
        private final AtomicBoolean delivered = new AtomicBoolean();

        private GatedChunkInputStream(byte[] chunk) {
            this.chunk = chunk.clone();
        }

        @Override
        public int read() {
            byte[] single = new byte[1];
            int count = read(single, 0, 1);
            return count < 0 ? -1 : Byte.toUnsignedInt(single[0]);
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            if (length == 0) {
                return 0;
            }
            awaitUninterruptibly(release);
            if (!delivered.compareAndSet(false, true)) {
                eof.countDown();
                return -1;
            }
            int count = Math.min(length, chunk.length);
            System.arraycopy(chunk, 0, bytes, offset, count);
            return count;
        }

        private void release() {
            release.countDown();
        }

        private boolean awaitEof() throws InterruptedException {
            return eof.await(1, TimeUnit.SECONDS);
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

    private static final class ZeroForeverInputStream extends InputStream {

        private final CountDownLatch firstRead = new CountDownLatch(1);
        private final AtomicInteger reads = new AtomicInteger();
        private volatile Thread readerThread;

        @Override
        public int read() {
            recordRead();
            return 0;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            recordRead();
            return 0;
        }

        @Override
        public void close() {
            // Deliberately ignore close so the pump must observe its owning session state.
        }

        private void recordRead() {
            readerThread = Thread.currentThread();
            reads.incrementAndGet();
            firstRead.countDown();
        }

        private boolean awaitFirstRead() throws InterruptedException {
            return firstRead.await(1, TimeUnit.SECONDS);
        }

        private int reads() {
            return reads.get();
        }

        private Thread readerThread() {
            return readerThread;
        }
    }

    private static final class ZeroThenByteInputStream extends InputStream {

        private final byte value;
        private int reads;

        private ZeroThenByteInputStream(byte value) {
            this.value = value;
        }

        @Override
        public int read() {
            if (reads++ == 0) {
                return value & 0xff;
            }
            return -1;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            if (reads++ == 0) {
                return 0;
            }
            if (reads == 2) {
                bytes[offset] = value;
                return 1;
            }
            return -1;
        }
    }
}
