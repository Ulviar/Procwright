/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.BoundedFailureReporterTestSupport;
import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.BufferUnderflowException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderMalfunctionError;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ProtocolSessionRequestCallbackFailureTest extends ProtocolSessionContractSupport {

    @Test
    void eagerMandatoryDecoderRuntimeFailuresAreTypedBeforeClaimAndLeaveRawSessionOpen() {
        for (int failingCreation : List.of(1, 2, 3, 4)) {
            for (Throwable cause : List.of(
                    new IllegalArgumentException("decoder creation " + failingCreation + " failed"),
                    new CoderMalfunctionError(new BufferUnderflowException()))) {
                DecoderCreationFailureCharset charset = new DecoderCreationFailureCharset(failingCreation, cause);
                TrackingInputStream stdout = new TrackingInputStream();
                TrackingInputStream stderr = new TrackingInputStream();
                ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream(), stdout, stderr);
                DefaultSession rawSession = session(process);
                try {
                    ProtocolSessionException failure = assertThrows(
                            ProtocolSessionException.class,
                            () -> new DefaultProtocolSession<>(rawSession, noOpAdapter(), options(charset)));

                    assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, failure.reason());
                    assertSame(cause, failure.getCause());
                    assertEquals(failingCreation, charset.decoderCreations());
                    assertTrue(process.isAlive());
                    assertFalse(rawSession.onExit().isDone());
                    assertEquals(0, stdout.reads());
                    assertEquals(0, stderr.reads());
                } finally {
                    rawSession.close();
                }
            }
        }
    }

    @Test
    void repeatedDecoderConstructionFailuresLeaveRawSessionReusable() throws Exception {
        GatedEofInputStream stdout = new GatedEofInputStream();
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        DefaultSession rawSession = session(process);
        DefaultProtocolSession<String, Byte> protocol = null;
        ExecutorService executor = Executors.newSingleThreadExecutor();
        CountDownLatch responseReadStarted = new CountDownLatch(1);
        try {
            for (int iteration = 0; iteration < 1_000; iteration++) {
                IllegalArgumentException cause = new IllegalArgumentException("decoder creation failed " + iteration);
                DecoderCreationFailureCharset charset = new DecoderCreationFailureCharset(1, cause);

                ProtocolSessionException failure = assertThrows(
                        ProtocolSessionException.class,
                        () -> new DefaultProtocolSession<>(rawSession, noOpAdapter(), options(charset)));

                assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, failure.reason());
                assertSame(cause, failure.getCause());
                assertTrue(process.isAlive());
                assertFalse(rawSession.onExit().isDone());
            }

            protocol = new DefaultProtocolSession<>(
                    rawSession,
                    new ProtocolAdapter<>() {
                        @Override
                        public void writeRequest(String request, ProtocolWriter writer) {}

                        @Override
                        public Byte readResponse(ProtocolReaders readers) {
                            responseReadStarted.countDown();
                            return readers.stdout().readByte();
                        }
                    },
                    ProtocolSessionSettings.defaults());
            DefaultProtocolSession<String, Byte> activeProtocol = protocol;
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> activeProtocol.request("request")));
            assertTrue(responseReadStarted.await(1, TimeUnit.SECONDS));
            assertTrue(stdout.awaitReadEntered());

            process.exitNaturally(17);
            stdout.releaseEof();
            ProtocolSessionException processExited =
                    assertInstanceOf(ProtocolSessionException.class, request.get(2, TimeUnit.SECONDS));

            assertEquals(ProtocolSessionException.Reason.PROCESS_EXITED, processExited.reason());
            assertEquals(17, processExited.exitCode().orElseThrow());
        } finally {
            stdout.releaseEof();
            if (protocol != null) {
                protocol.close();
            } else {
                rawSession.close();
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void eagerMandatoryDecoderFatalErrorsPreserveIdentityAndLeaveRawSessionOpen() {
        for (int failingCreation : List.of(1, 2, 3, 4)) {
            AssertionError cause = new AssertionError("fatal decoder creation " + failingCreation);
            DecoderCreationFailureCharset charset = new DecoderCreationFailureCharset(failingCreation, cause);
            TrackingInputStream stdout = new TrackingInputStream();
            TrackingInputStream stderr = new TrackingInputStream();
            ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream(), stdout, stderr);
            DefaultSession rawSession = session(process);
            try {
                AssertionError thrown = assertThrows(
                        AssertionError.class,
                        () -> new DefaultProtocolSession<>(rawSession, noOpAdapter(), options(charset)));

                assertSame(cause, thrown);
                assertEquals(failingCreation, charset.decoderCreations());
                assertTrue(process.isAlive());
                assertFalse(rawSession.onExit().isDone());
                assertEquals(0, stdout.reads());
                assertEquals(0, stderr.reads());
            } finally {
                rawSession.close();
            }
        }
    }

    @Test
    void directResponseCallbackErrorIsTheTerminalFailureByIdentity() throws Exception {
        AssertionError callbackError = new AssertionError("response callback failed");
        ControllableProcess process = new ControllableProcess();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                throw callbackError;
            }
        };
        DefaultProtocolSession<String, String> protocol =
                new DefaultProtocolSession<>(session(process), adapter, ProtocolSessionSettings.defaults());
        try {
            AssertionError current = assertThrows(AssertionError.class, () -> protocol.request("request"));
            assertSame(callbackError, current);
            protocol.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());

            AssertionError followUp = assertThrows(AssertionError.class, () -> protocol.request("retry"));
            assertSame(callbackError, followUp);
            assertEquals(0, callbackError.getSuppressed().length);
        } finally {
            protocol.close();
        }
    }

    @Test
    void callbackErrorSupersedesTheTypedFailureCaughtByTheCallback() throws Exception {
        AssertionError callbackError = new AssertionError("callback rejected oversized response");
        AtomicReference<ProtocolSessionException> caughtFailure = new AtomicReference<>();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                try {
                    readers.stdout().readLine(1);
                    throw new AssertionError("response limit was not enforced");
                } catch (ProtocolSessionException failure) {
                    caughtFailure.set(failure);
                    throw callbackError;
                }
            }
        };
        ControllableProcess process = new ControllableProcess(
                OutputStream.nullOutputStream(),
                new java.io.ByteArrayInputStream("ab\n".getBytes(StandardCharsets.UTF_8)),
                InputStream.nullInputStream());
        DefaultProtocolSession<String, String> protocol =
                new DefaultProtocolSession<>(session(process), adapter, ProtocolSessionSettings.defaults());
        try {
            AssertionError current = assertThrows(AssertionError.class, () -> protocol.request("request"));
            ProtocolSessionException responseFailure = caughtFailure.get();
            assertSame(callbackError, current);
            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, responseFailure.reason());
            assertFailureGraphDoesNotContain(responseFailure, callbackError);
            assertEquals(0, callbackError.getSuppressed().length);
            assertEquals(0, responseFailure.getSuppressed().length);

            AssertionError followUp = assertThrows(AssertionError.class, () -> protocol.request("retry"));
            assertSame(callbackError, followUp);
        } finally {
            protocol.close();
        }
    }

    @Test
    void callbackErrorLosingToConcurrentPumpErrorIsReportedSeparately() throws Exception {
        AssertionError pumpError = new AssertionError("stdout pump failed");
        AssertionError callbackError = new AssertionError("response callback failed");
        GatedErrorInputStream stdout = new GatedErrorInputStream(pumpError);
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                callbackStarted.countDown();
                awaitUninterruptibly(releaseCallback);
                throw callbackError;
            }
        };
        ControllableProcess process =
                new ControllableProcess(OutputStream.nullOutputStream(), stdout, InputStream.nullInputStream());
        List<Throwable> reported = new CopyOnWriteArrayList<>();
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "protocol-callback-race-test");
            thread.setUncaughtExceptionHandler((ignored, failure) -> reported.add(failure));
            return thread;
        });
        DefaultProtocolSession<String, String> protocol = null;
        try {
            protocol = new DefaultProtocolSession<>(
                    session(process),
                    adapter,
                    ProtocolSessionSettings.defaults(),
                    ProtocolSessionTestDependencies.withCallbackRunner(new DirectProtocolCallbackRunner()));
            DefaultProtocolSession<String, String> activeProtocol = protocol;
            Future<Throwable> request = executor.submit(() -> captureFailure(() -> activeProtocol.request("request")));
            assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));

            stdout.releaseFailure();
            protocol.onExit().handle((ignored, failure) -> null).get(1, TimeUnit.SECONDS);
            releaseCallback.countDown();

            assertSame(pumpError, request.get(1, TimeUnit.SECONDS));
            assertTrue(BoundedFailureReporterTestSupport.awaitSharedSettlement(Duration.ofSeconds(1)));
            assertEquals(
                    1,
                    reported.stream()
                            .filter(failure -> failure == callbackError)
                            .count());
        } finally {
            releaseCallback.countDown();
            if (protocol != null) {
                protocol.close();
            }
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private static final class DirectProtocolCallbackRunner implements DefaultProtocolSession.ProtocolCallbackRunner {

        @Override
        public <T> T run(
                String threadPrefix,
                long deadlineNanos,
                BoundedTaskRunner.CancellationSignal cancellation,
                BoundedTaskRunner.TaskAbandonmentHandler abandonmentHandler,
                BoundedTaskRunner.Task<T> task)
                throws TimeoutException, InterruptedException, ExecutionException {
            try {
                return task.run();
            } catch (Throwable failure) {
                throw new ExecutionException(failure);
            }
        }
    }

    private static final class DecoderCreationFailureCharset extends Charset {

        private final int failingCreation;
        private final Throwable failure;
        private int decoderCreations;

        private DecoderCreationFailureCharset(int failingCreation, Throwable failure) {
            super(
                    "X-Procwright-Protocol-Decoder-Creation-" + failingCreation + "-"
                            + failure.getClass().getSimpleName(),
                    new String[0]);
            this.failingCreation = failingCreation;
            this.failure = failure;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            decoderCreations++;
            if (decoderCreations == failingCreation) {
                if (failure instanceof RuntimeException runtimeException) {
                    throw runtimeException;
                }
                throw (Error) failure;
            }
            return passthroughDecoder(this);
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }

        private int decoderCreations() {
            return decoderCreations;
        }
    }

    private static final class TrackingInputStream extends InputStream {

        private final AtomicInteger reads = new AtomicInteger();

        @Override
        public int read() {
            reads.incrementAndGet();
            return -1;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            reads.incrementAndGet();
            return -1;
        }

        private int reads() {
            return reads.get();
        }
    }
}
