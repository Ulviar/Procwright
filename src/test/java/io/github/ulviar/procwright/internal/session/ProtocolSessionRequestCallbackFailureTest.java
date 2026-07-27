/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
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
    void eagerMandatoryDecoderRuntimeFailuresAreTypedAndRollbackTheProcess() {
        for (int failingCreation : List.of(1, 2, 3, 4)) {
            for (Throwable cause : List.of(
                    new IllegalArgumentException("decoder creation " + failingCreation + " failed"),
                    new CoderMalfunctionError(new BufferUnderflowException()))) {
                DecoderCreationFailureCharset charset = new DecoderCreationFailureCharset(failingCreation, cause);
                TrackingInputStream stdout = new TrackingInputStream();
                TrackingInputStream stderr = new TrackingInputStream();
                ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream(), stdout, stderr);
                ProtocolSessionException failure = assertThrows(
                        ProtocolSessionException.class,
                        () -> protocolSession(process, noOpAdapter(), options(charset)));

                assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, failure.reason());
                assertSame(cause, failure.getCause());
                assertEquals(failingCreation, charset.decoderCreations());
                assertFalse(process.isAlive());
                assertEquals(0, stdout.reads());
                assertEquals(0, stderr.reads());
            }
        }
    }

    @Test
    void eagerMandatoryDecoderFatalErrorsPreserveIdentityAndRollbackTheProcess() {
        for (int failingCreation : List.of(1, 2, 3, 4)) {
            AssertionError cause = new AssertionError("fatal decoder creation " + failingCreation);
            DecoderCreationFailureCharset charset = new DecoderCreationFailureCharset(failingCreation, cause);
            TrackingInputStream stdout = new TrackingInputStream();
            TrackingInputStream stderr = new TrackingInputStream();
            ControllableProcess process = new ControllableProcess(OutputStream.nullOutputStream(), stdout, stderr);
            AssertionError thrown =
                    assertThrows(AssertionError.class, () -> protocolSession(process, noOpAdapter(), options(charset)));

            assertSame(cause, thrown);
            assertEquals(failingCreation, charset.decoderCreations());
            assertFalse(process.isAlive());
            assertEquals(0, stdout.reads());
            assertEquals(0, stderr.reads());
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
                protocolSession(process, adapter, ProtocolSessionSettings.defaults());
        try {
            AssertionError current = assertThrows(AssertionError.class, () -> protocol.request("request"));
            assertSame(callbackError, current);
            ExecutionException exitFailure = assertThrows(
                    ExecutionException.class, () -> protocol.onExit().get(1, TimeUnit.SECONDS));
            assertSame(current, exitFailure.getCause());
            assertFalse(process.isAlive());

            AssertionError followUp = assertThrows(AssertionError.class, () -> protocol.request("retry"));
            assertSame(callbackError, followUp);
            assertEquals(0, callbackError.getSuppressed().length);
        } finally {
            protocol.close();
        }
    }

    @Test
    void callbackErrorDoesNotReplaceTheTypedFailureCaughtByTheCallback() throws Exception {
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
                protocolSession(process, adapter, ProtocolSessionSettings.defaults());
        try {
            ProtocolSessionException current =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("request"));
            ProtocolSessionException responseFailure = caughtFailure.get();
            assertEquals(responseFailure.reason(), current.reason());
            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, responseFailure.reason());
            assertFailureGraphDoesNotContain(responseFailure, callbackError);
            assertEquals(0, callbackError.getSuppressed().length);
            assertEquals(0, responseFailure.getSuppressed().length);

            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("retry"));
            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, followUp.reason());
        } finally {
            protocol.close();
        }
    }

    @Test
    void callbackErrorLosingToConcurrentPumpErrorDoesNotChangeThePumpOutcome() throws Exception {
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
        ExecutorService executor = Executors.newSingleThreadExecutor();
        DefaultProtocolSession<String, String> protocol = null;
        try {
            protocol = protocolSession(
                    process,
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
            assertSame(pumpError, captureFailure(() -> activeProtocol.request("retry")));
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
                TimedTaskRunner.CancellationSignal cancellation,
                TimedTaskRunner.AbandonmentHandler abandonmentHandler,
                TimedTaskRunner.Task<T> task)
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

    private static void assertFailureGraphDoesNotContain(Throwable root, Throwable forbidden) {
        IdentityHashMap<Throwable, Boolean> visited = new IdentityHashMap<>();
        ArrayList<Throwable> pending = new ArrayList<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            Throwable current = pending.remove(pending.size() - 1);
            assertNotSame(forbidden, current, "failure graph unexpectedly references the forbidden failure");
            if (visited.put(current, Boolean.TRUE) != null) {
                continue;
            }
            if (current.getCause() != null) {
                pending.add(current.getCause());
            }
            pending.addAll(List.of(current.getSuppressed()));
        }
    }

    private static final class GatedErrorInputStream extends InputStream {

        private final Error failure;
        private final CountDownLatch releaseFailure = new CountDownLatch(1);

        GatedErrorInputStream(Error failure) {
            this.failure = failure;
        }

        @Override
        public int read() {
            awaitUninterruptibly(releaseFailure);
            throw failure;
        }

        @Override
        public int read(byte[] bytes, int offset, int length) {
            Objects.checkFromIndexSize(offset, length, bytes.length);
            return length == 0 ? 0 : read();
        }

        void releaseFailure() {
            releaseFailure.countDown();
        }
    }
}
