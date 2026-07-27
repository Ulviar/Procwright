/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.BlockingUntilClosedInputStream;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.ControllableProcess;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.ReplyingOutputStream;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.ResponseInputStream;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.captureFailure;
import static io.github.ulviar.procwright.internal.session.LineSessionTestFixtures.openLineSession;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.LineSessionSettings;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSessionException;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

final class DefaultLineSessionWriterFailureTest {

    @Test
    void preStartFailureLeavesSessionReusable() throws Exception {
        IllegalStateException startFailure = new IllegalStateException("writer unavailable");
        AtomicBoolean failNextStart = new AtomicBoolean(true);
        ResponseInputStream stdout = new ResponseInputStream();
        ReplyingOutputStream stdin = new ReplyingOutputStream(stdout);
        ControllableProcess process = new ControllableProcess(stdin, stdout, InputStream.nullInputStream());
        DefaultLineSession lineSession = openLineSession(
                process,
                LineSessionSettings.defaults(),
                LineSessionTestDependencies.withTaskRunner((threadPrefix, deadlineNanos, start, task) -> {
                    if (failNextStart.compareAndSet(true, false)) {
                        throw new ExecutionException(startFailure);
                    }
                    TimedTaskRunner.runTracked(threadPrefix, deadlineNanos, start, task);
                }));
        try {
            LineSessionException failure = assertThrows(
                    LineSessionException.class,
                    () -> lineSession.requestEncoded(
                            "first\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));

            assertEquals(LineSessionException.Reason.FAILURE, failure.reason());
            assertSame(startFailure, failure.getCause());
            assertEquals(0, stdin.writeCalls());
            assertFalse(lineSession.onExit().isDone());

            LineResponse response =
                    lineSession.requestEncoded("second\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1));
            assertEquals("ok", response.text());
            assertEquals("second\n", stdin.writtenText());
        } finally {
            lineSession.close();
            stdout.close();
        }
    }

    @Test
    void callerInterruptAfterControlledPartialWriteClosesSessionAndPreservesTypedFailure() throws Exception {
        BlockingAfterFirstByteOutputStream stdin = new BlockingAfterFirstByteOutputStream();
        BlockingUntilClosedInputStream stdout = new BlockingUntilClosedInputStream();
        ControllableProcess process = new ControllableProcess(stdin, stdout, InputStream.nullInputStream());
        DefaultLineSession lineSession = openLineSession(process, LineSessionSettings.defaults());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Thread> requestCaller = new AtomicReference<>();
        try {
            Future<Throwable> request = executor.submit(() -> {
                requestCaller.set(Thread.currentThread());
                return captureFailure(() -> lineSession.requestEncoded(
                        "request\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(30)));
            });
            assertTrue(stdin.awaitFirstByte(), "writer did not reach the controlled partial-write boundary");
            requestCaller.get().interrupt();

            Throwable thrown = request.get(10, TimeUnit.SECONDS);
            assertTrue(thrown instanceof LineSessionException);
            LineSessionException interrupted = (LineSessionException) thrown;
            assertEquals(LineSessionException.Reason.FAILURE, interrupted.reason());
            assertTrue(interrupted.getCause() instanceof InterruptedException);
            assertArrayEquals(new byte[] {'r'}, stdin.writtenBytes());
            assertTrue(stdin.awaitWriterStopped());
            assertTrue(stdin.wasInterrupted());
            ExecutionException exitFailure = assertThrows(
                    ExecutionException.class, () -> lineSession.onExit().get(1, TimeUnit.SECONDS));
            assertSame(interrupted, exitFailure.getCause());

            LineSessionException followUp = assertThrows(
                    LineSessionException.class,
                    () -> lineSession.requestEncoded(
                            "retry\n".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));
            assertEquals(LineSessionException.Reason.FAILURE, followUp.reason());
            assertTrue(followUp.getMessage().contains("closed by an earlier failure"));
            assertEquals(1, stdin.writeCalls());
        } finally {
            stdin.releaseWriter();
            try {
                lineSession.close();
            } finally {
                assertTrue(stdin.awaitClosed());
                stdout.close();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void delegateIllegalStateExceptionIsFailureAndWriterIsFailStopped() throws Exception {
        IllegalStateException writeFailure = new IllegalStateException("delegate state failed");
        PrefixThenThrowingOutputStream stdin = new PrefixThenThrowingOutputStream(writeFailure);
        ControllableProcess process =
                new ControllableProcess(stdin, InputStream.nullInputStream(), InputStream.nullInputStream());
        try (DefaultLineSession lineSession = openLineSession(process, LineSessionSettings.defaults())) {
            LineSessionException failure = assertThrows(
                    LineSessionException.class,
                    () -> lineSession.requestEncoded("abcd".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));

            assertEquals(LineSessionException.Reason.FAILURE, failure.reason());
            assertSame(writeFailure, failure.getCause());
            assertEquals(1, stdin.writeCalls());
            assertEquals("ab", stdin.writtenText());
            ExecutionException exitFailure = assertThrows(
                    ExecutionException.class, () -> lineSession.onExit().get(1, TimeUnit.SECONDS));
            assertSame(failure, exitFailure.getCause());
            assertFalse(process.isAlive());

            LineSessionException followUp = assertThrows(
                    LineSessionException.class,
                    () -> lineSession.requestEncoded("retry".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));
            assertEquals(LineSessionException.Reason.FAILURE, followUp.reason());
            assertSame(failure, followUp.getCause());
            assertSame(writeFailure, followUp.getCause().getCause());
            assertEquals(1, stdin.writeCalls());
        }
    }

    @Test
    void delegateIoFailureIsBrokenPipeAndRemainsTheTerminalReason() throws Exception {
        IOException writeFailure = new IOException("pipe write failed");
        PrefixThenThrowingOutputStream stdin = new PrefixThenThrowingOutputStream(writeFailure);
        ControllableProcess process =
                new ControllableProcess(stdin, InputStream.nullInputStream(), InputStream.nullInputStream());
        try (DefaultLineSession lineSession = openLineSession(process, LineSessionSettings.defaults())) {
            LineSessionException failure = assertThrows(
                    LineSessionException.class,
                    () -> lineSession.requestEncoded("abcd".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));

            assertEquals(LineSessionException.Reason.BROKEN_PIPE, failure.reason());
            assertSame(writeFailure, failure.getCause());
            assertEquals(1, stdin.writeCalls());
            assertEquals("ab", stdin.writtenText());
            ExecutionException exitFailure = assertThrows(
                    ExecutionException.class, () -> lineSession.onExit().get(1, TimeUnit.SECONDS));
            assertSame(failure, exitFailure.getCause());
            assertFalse(process.isAlive());

            LineSessionException followUp = assertThrows(
                    LineSessionException.class,
                    () -> lineSession.requestEncoded("retry".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));
            assertEquals(LineSessionException.Reason.BROKEN_PIPE, followUp.reason());
            assertSame(failure, followUp.getCause());
            assertSame(writeFailure, followUp.getCause().getCause());
            assertEquals(1, stdin.writeCalls());
        }
    }

    @Test
    void delegateErrorClosesSessionAndRemainsTerminalByIdentity() throws Exception {
        AssertionError writeFailure = new AssertionError("delegate invariant failed");
        PrefixThenThrowingOutputStream stdin = new PrefixThenThrowingOutputStream(writeFailure);
        ControllableProcess process =
                new ControllableProcess(stdin, InputStream.nullInputStream(), InputStream.nullInputStream());
        try (DefaultLineSession lineSession = openLineSession(process, LineSessionSettings.defaults())) {
            AssertionError thrown = assertThrows(
                    AssertionError.class,
                    () -> lineSession.requestEncoded("abcd".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));

            assertSame(writeFailure, thrown);
            assertEquals(1, stdin.writeCalls());
            assertEquals("ab", stdin.writtenText());
            ExecutionException exitFailure = assertThrows(
                    ExecutionException.class, () -> lineSession.onExit().get(1, TimeUnit.SECONDS));
            assertSame(writeFailure, exitFailure.getCause());
            assertFalse(process.isAlive());

            AssertionError followUp = assertThrows(
                    AssertionError.class,
                    () -> lineSession.requestEncoded("retry".getBytes(StandardCharsets.UTF_8), Duration.ofSeconds(1)));
            assertSame(writeFailure, followUp);
            assertEquals(1, stdin.writeCalls());
        }
    }

    private static final class PrefixThenThrowingOutputStream extends OutputStream {

        private final Throwable failure;
        private final ByteArrayOutputStream written = new ByteArrayOutputStream();
        private int writeCalls;

        private PrefixThenThrowingOutputStream(Throwable failure) {
            this.failure = failure;
        }

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) throws IOException {
            writeCalls++;
            written.write(bytes, offset, Math.min(2, length));
            if (failure instanceof IOException exception) {
                throw exception;
            }
            if (failure instanceof RuntimeException exception) {
                throw exception;
            }
            throw (Error) failure;
        }

        private int writeCalls() {
            return writeCalls;
        }

        private String writtenText() {
            return written.toString(StandardCharsets.UTF_8);
        }
    }

    private static final class BlockingAfterFirstByteOutputStream extends OutputStream {

        private final ByteArrayOutputStream written = new ByteArrayOutputStream();
        private final CountDownLatch firstByte = new CountDownLatch(1);
        private final CountDownLatch releaseWriter = new CountDownLatch(1);
        private final CountDownLatch writerStopped = new CountDownLatch(1);
        private final CountDownLatch closed = new CountDownLatch(1);
        private final AtomicBoolean interrupted = new AtomicBoolean();
        private final AtomicInteger writeCalls = new AtomicInteger();

        @Override
        public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }

        @Override
        public void write(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            writeCalls.incrementAndGet();
            if (length > 0) {
                synchronized (written) {
                    written.write(bytes[offset]);
                }
            }
            firstByte.countDown();
            try {
                releaseWriter.await();
            } catch (InterruptedException exception) {
                interrupted.set(true);
                Thread.currentThread().interrupt();
            } finally {
                writerStopped.countDown();
            }
        }

        @Override
        public void close() {
            closed.countDown();
        }

        private boolean awaitFirstByte() throws InterruptedException {
            return firstByte.await(5, TimeUnit.SECONDS);
        }

        private boolean awaitWriterStopped() throws InterruptedException {
            return writerStopped.await(1, TimeUnit.SECONDS);
        }

        private boolean awaitClosed() throws InterruptedException {
            return closed.await(5, TimeUnit.SECONDS);
        }

        private byte[] writtenBytes() {
            synchronized (written) {
                return written.toByteArray();
            }
        }

        private boolean wasInterrupted() {
            return interrupted.get();
        }

        private int writeCalls() {
            return writeCalls.get();
        }

        private void releaseWriter() {
            releaseWriter.countDown();
        }
    }
}
