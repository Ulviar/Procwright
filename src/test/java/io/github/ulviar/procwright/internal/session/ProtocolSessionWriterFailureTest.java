/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.internal.WorkerPoolSettings;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.io.ByteArrayOutputStream;
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
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

final class ProtocolSessionWriterFailureTest extends ProtocolSessionContractSupport {

    @Test
    void textWriterIsFailStoppedAfterPartialDelegateWriteFailure() throws Exception {
        IOException writeFailure = new IOException("partial write failed");
        PrefixThenThrowingOutputStream stdin = new PrefixThenThrowingOutputStream(writeFailure);
        ControllableProcess process =
                new ControllableProcess(stdin, InputStream.nullInputStream(), InputStream.nullInputStream());
        List<ProtocolSessionException> observed = new ArrayList<>();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        writer.write("abcd");
                    } catch (ProtocolSessionException failure) {
                        observed.add(failure);
                    }
                }
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "fallback";
            }
        };
        try (DefaultProtocolSession<String, String> protocol =
                protocolSession(process, adapter, ProtocolSessionSettings.defaults())) {
            ProtocolSessionException failure =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("ignored"));

            assertEquals(ProtocolSessionException.Reason.BROKEN_PIPE, failure.reason());
            assertSame(writeFailure, failure.getCause());
            assertEquals(2, observed.size());
            assertSame(observed.get(0), observed.get(1));
            assertSame(failure, observed.get(0));
            assertEquals(1, stdin.writeCalls());
            assertEquals("ab", stdin.writtenText());
            assertExitFailedWith(protocol, failure);
            assertFalse(process.isAlive());

            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("again"));
            assertEquals(ProtocolSessionException.Reason.BROKEN_PIPE, followUp.reason());
            assertSame(failure, followUp.getCause());
            assertSame(writeFailure, followUp.getCause().getCause());
            assertEquals(1, stdin.writeCalls());
        }
    }

    @Test
    void requestEncoderRuntimeFailureIsRecordedAndFailStopsCaughtRetry() throws Exception {
        IllegalArgumentException encoderFailure = new IllegalArgumentException("encoder creation failed");
        EncoderCreationFailureCharset charset = new EncoderCreationFailureCharset(encoderFailure);
        ControllableProcess process = new ControllableProcess();
        List<ProtocolSessionException> observed = new ArrayList<>();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        writer.write(request);
                    } catch (ProtocolSessionException failure) {
                        observed.add(failure);
                    }
                }
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "fallback";
            }
        };
        try (DefaultProtocolSession<String, String> protocol = protocolSession(process, adapter, options(charset))) {
            ProtocolSessionException failure =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("request"));

            assertEquals(ProtocolSessionException.Reason.FAILURE, failure.reason());
            assertSame(encoderFailure, failure.getCause());
            assertEquals(2, observed.size());
            assertSame(failure, observed.get(0));
            assertSame(observed.get(0), observed.get(1));
            assertEquals(1, charset.encoderCreations());
            assertExitFailedWith(protocol, failure);
            assertFalse(process.isAlive());

            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("again"));
            assertEquals(ProtocolSessionException.Reason.FAILURE, followUp.reason());
            assertSame(failure, followUp.getCause());
            assertSame(encoderFailure, followUp.getCause().getCause());
        }
    }

    @Test
    void requestEncoderPreflightFailureIsRecordedAndFailStopsCaughtRetry() throws Exception {
        NoProgressEncoderCharset charset = new NoProgressEncoderCharset();
        ControllableProcess process = new ControllableProcess();
        List<ProtocolSessionException> observed = new ArrayList<>();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        writer.write(request);
                    } catch (ProtocolSessionException failure) {
                        observed.add(failure);
                    }
                }
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "fallback";
            }
        };
        try (DefaultProtocolSession<String, String> protocol = protocolSession(process, adapter, options(charset))) {
            ProtocolSessionException failure =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("request"));

            assertEquals(ProtocolSessionException.Reason.FAILURE, failure.reason());
            assertTrue(failure.getCause() instanceof IllegalStateException);
            assertTrue(failure.getCause().getMessage().contains("without consuming input or producing output"));
            assertEquals(2, observed.size());
            assertSame(failure, observed.get(0));
            assertSame(observed.get(0), observed.get(1));
            assertEquals(1, charset.encoderCreations());
            assertExitFailedWith(protocol, failure);
            assertFalse(process.isAlive());

            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("again"));
            assertEquals(ProtocolSessionException.Reason.FAILURE, followUp.reason());
            assertSame(failure, followUp.getCause());
        }
    }

    @Test
    void caughtRequestEncoderErrorSelectsATypedTerminalFailure() throws Exception {
        AssertionError encoderFailure = new AssertionError("encoder invariant failed");
        EncoderCreationFailureCharset charset = new EncoderCreationFailureCharset(encoderFailure);
        ControllableProcess process = new ControllableProcess();
        List<AssertionError> observed = new ArrayList<>();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                try {
                    writer.write(request);
                } catch (AssertionError error) {
                    observed.add(error);
                }
                writer.write("retry");
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "fallback";
            }
        };
        try (DefaultProtocolSession<String, String> protocol = protocolSession(process, adapter, options(charset))) {
            ProtocolSessionException thrown =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("request"));

            assertEquals(ProtocolSessionException.Reason.FAILURE, thrown.reason());
            assertEquals(List.of(encoderFailure), observed);
            assertEquals(1, charset.encoderCreations());
            assertExitFailedWith(protocol, thrown);
            assertFalse(process.isAlive());

            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("again"));
            assertEquals(ProtocolSessionException.Reason.FAILURE, followUp.reason());
        }
    }

    @Test
    void caughtEncoderErrorPreventsResponseDecodingAndSelectsTypedFailure() throws Exception {
        AssertionError encoderFailure = new AssertionError("encoder invariant failed");
        EncoderCreationFailureCharset charset = new EncoderCreationFailureCharset(encoderFailure);
        CountingOutputStream stdin = new CountingOutputStream();
        ControllableProcess process =
                new ControllableProcess(stdin, InputStream.nullInputStream(), InputStream.nullInputStream());
        AtomicReference<AssertionError> observed = new AtomicReference<>();
        AtomicBoolean responseDecoderCalled = new AtomicBoolean();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                try {
                    writer.write(request);
                } catch (AssertionError error) {
                    observed.set(error);
                }
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                responseDecoderCalled.set(true);
                return "fallback";
            }
        };
        try (DefaultProtocolSession<String, String> protocol = protocolSession(process, adapter, options(charset))) {
            ProtocolSessionException thrown =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("request"));

            assertEquals(ProtocolSessionException.Reason.FAILURE, thrown.reason());
            assertSame(encoderFailure, observed.get());
            assertEquals(1, charset.encoderCreations());
            assertEquals(0, stdin.writeCalls());
            assertFalse(responseDecoderCalled.get());
            assertExitFailedWith(protocol, thrown);
            assertFalse(process.isAlive());

            int writesAfterFailure = stdin.writeCalls();
            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("again"));
            assertEquals(ProtocolSessionException.Reason.FAILURE, followUp.reason());
            assertEquals(writesAfterFailure, stdin.writeCalls());
        }
    }

    @Test
    void delegateIllegalStateExceptionIsNotTreatedAsClosed() throws Exception {
        assertCaughtRuntimeDelegateFailureIsTerminal(new IllegalStateException("delegate state failed"));
    }

    @Test
    void lifecycleClosedStdinIsTreatedAsClosed() throws Exception {
        ControllableProcess process = new ControllableProcess();
        AtomicReference<DefaultSession> rawSession = new AtomicReference<>();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.write(request);
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "unused";
            }
        };
        try (DefaultProtocolSession<String, String> protocol =
                protocolSession(process, adapter, ProtocolSessionSettings.defaults(), rawSession::set)) {
            rawSession.get().closeStdin();

            ProtocolSessionException failure =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("request"));

            assertEquals(ProtocolSessionException.Reason.CLOSED, failure.reason());
            assertTrue(failure.getCause() instanceof SessionStdinClosedException);
        }
        assertFalse(process.isAlive());
    }

    @Test
    void delegateRuntimeFailuresAreRecordedBeforeCaughtRetry() throws Exception {
        for (RuntimeException failure :
                List.of(new SecurityException("write denied"), new RuntimeException("delegate failed"))) {
            assertCaughtRuntimeDelegateFailureIsTerminal(failure);
        }
    }

    @Test
    void caughtDelegateErrorSelectsATypedTerminalFailure() throws Exception {
        AssertionError writeFailure = new AssertionError("delegate invariant failed");
        PrefixThenThrowingOutputStream stdin = new PrefixThenThrowingOutputStream(writeFailure);
        ControllableProcess process =
                new ControllableProcess(stdin, InputStream.nullInputStream(), InputStream.nullInputStream());
        List<AssertionError> observed = new ArrayList<>();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                try {
                    writer.write("abcd");
                } catch (AssertionError error) {
                    observed.add(error);
                }
                writer.write("retry");
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "fallback";
            }
        };
        try (DefaultProtocolSession<String, String> protocol =
                protocolSession(process, adapter, ProtocolSessionSettings.defaults())) {
            ProtocolSessionException thrown =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("ignored"));

            assertEquals(ProtocolSessionException.Reason.FAILURE, thrown.reason());
            assertEquals(List.of(writeFailure), observed);
            assertEquals(1, stdin.writeCalls());
            assertEquals("ab", stdin.writtenText());
            assertExitFailedWith(protocol, thrown);
            assertFalse(process.isAlive());

            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("again"));
            assertEquals(ProtocolSessionException.Reason.FAILURE, followUp.reason());
        }
    }

    @Test
    void caughtDelegateErrorPreventsResponseDecodingAndSelectsTypedFailure() throws Exception {
        AssertionError writeFailure = new AssertionError("delegate invariant failed");
        PrefixThenThrowingOutputStream stdin = new PrefixThenThrowingOutputStream(writeFailure);
        ControllableProcess process =
                new ControllableProcess(stdin, InputStream.nullInputStream(), InputStream.nullInputStream());
        AtomicReference<AssertionError> observed = new AtomicReference<>();
        AtomicBoolean responseDecoderCalled = new AtomicBoolean();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                try {
                    writer.write("abcd");
                } catch (AssertionError error) {
                    observed.set(error);
                }
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                responseDecoderCalled.set(true);
                return "fallback";
            }
        };
        try (DefaultProtocolSession<String, String> protocol =
                protocolSession(process, adapter, ProtocolSessionSettings.defaults())) {
            ProtocolSessionException thrown =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("ignored"));

            assertEquals(ProtocolSessionException.Reason.FAILURE, thrown.reason());
            assertSame(writeFailure, observed.get());
            assertEquals(1, stdin.writeCalls());
            assertEquals("ab", stdin.writtenText());
            assertFalse(responseDecoderCalled.get());
            assertExitFailedWith(protocol, thrown);
            assertFalse(process.isAlive());

            int writesAfterFailure = stdin.writeCalls();
            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("again"));
            assertEquals(ProtocolSessionException.Reason.FAILURE, followUp.reason());
            assertEquals(writesAfterFailure, stdin.writeCalls());
        }
    }

    @Test
    void protocolPoolRetiresWorkerAfterDelegateIllegalStateFailure() {
        IllegalStateException writeFailure = new IllegalStateException("delegate state failed");
        PrefixThenThrowingOutputStream failingStdin = new PrefixThenThrowingOutputStream(writeFailure);
        AtomicInteger workerCreations = new AtomicInteger();
        List<ControllableProcess> processes = new ArrayList<>();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                try {
                    writer.write(request);
                } catch (ProtocolSessionException ignored) {
                    // A caught writer failure must still fail this request and retire its worker.
                }
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "fallback";
            }
        };
        Supplier<ProtocolSession<String, String>> workerFactory = () -> {
            int workerNumber = workerCreations.incrementAndGet();
            OutputStream stdin = workerNumber == 1 ? failingStdin : new ByteArrayOutputStream();
            ControllableProcess process =
                    new ControllableProcess(stdin, InputStream.nullInputStream(), InputStream.nullInputStream());
            processes.add(process);
            return protocolSession(process, adapter, ProtocolSessionSettings.defaults());
        };

        try (DefaultPooledProtocolSession<String, String> pool =
                new DefaultPooledProtocolSession<>(workerFactory, WorkerPoolSettings.defaults())) {
            ProtocolSessionException first = assertThrows(ProtocolSessionException.class, () -> pool.request("first"));

            assertEquals(ProtocolSessionException.Reason.FAILURE, first.reason());
            assertSame(writeFailure, first.getCause());
            assertEquals(1, failingStdin.writeCalls());
            assertEquals(1, workerCreations.get());
            assertFalse(processes.get(0).isAlive());

            assertEquals("fallback", pool.request("second"));
            assertEquals(1, pool.metrics().retired());
            assertEquals(1L, pool.metrics().retireReasons().get(PooledWorkerRetireReason.WORKER_FAILED));
            assertEquals(2, workerCreations.get());
            assertTrue(processes.get(1).isAlive());
        }
    }

    private static void assertCaughtRuntimeDelegateFailureIsTerminal(RuntimeException writeFailure) throws Exception {
        PrefixThenThrowingOutputStream stdin = new PrefixThenThrowingOutputStream(writeFailure);
        ControllableProcess process =
                new ControllableProcess(stdin, InputStream.nullInputStream(), InputStream.nullInputStream());
        List<ProtocolSessionException> observed = new ArrayList<>();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        writer.write("abcd");
                    } catch (ProtocolSessionException failure) {
                        observed.add(failure);
                    }
                }
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "fallback";
            }
        };
        try (DefaultProtocolSession<String, String> protocol =
                protocolSession(process, adapter, ProtocolSessionSettings.defaults())) {
            ProtocolSessionException failure =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("ignored"));

            assertEquals(ProtocolSessionException.Reason.FAILURE, failure.reason());
            assertSame(writeFailure, failure.getCause());
            assertEquals(2, observed.size());
            assertSame(observed.get(0), observed.get(1));
            assertSame(failure, observed.get(0));
            assertEquals(1, stdin.writeCalls());
            assertEquals("ab", stdin.writtenText());
            assertExitFailedWith(protocol, failure);
            assertFalse(process.isAlive());

            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("again"));
            assertEquals(ProtocolSessionException.Reason.FAILURE, followUp.reason());
        }
    }

    private static void assertExitFailedWith(DefaultProtocolSession<?, ?> protocol, Throwable expectedFailure) {
        ExecutionException exitFailure =
                assertThrows(ExecutionException.class, () -> protocol.onExit().get(1, TimeUnit.SECONDS));
        assertSame(expectedFailure, exitFailure.getCause());
    }

    private static final class EncoderCreationFailureCharset extends Charset {

        private final Throwable failure;
        private int encoderCreations;

        private EncoderCreationFailureCharset(Throwable failure) {
            super("X-Procwright-Protocol-Encoder-Creation-" + failure.getClass().getSimpleName(), new String[0]);
            this.failure = failure;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return passthroughDecoder(this);
        }

        @Override
        public CharsetEncoder newEncoder() {
            encoderCreations++;
            if (failure instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            throw (Error) failure;
        }

        private int encoderCreations() {
            return encoderCreations;
        }
    }

    private static final class NoProgressEncoderCharset extends Charset {

        private int encoderCreations;

        private NoProgressEncoderCharset() {
            super("X-Procwright-Protocol-No-Progress-Encoder", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return passthroughDecoder(this);
        }

        @Override
        public CharsetEncoder newEncoder() {
            encoderCreations++;
            return new CharsetEncoder(this, 1, 1) {
                @Override
                protected CoderResult encodeLoop(CharBuffer input, ByteBuffer output) {
                    return CoderResult.OVERFLOW;
                }
            };
        }

        private int encoderCreations() {
            return encoderCreations;
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
}
