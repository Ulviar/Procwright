/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.internal.ProtocolSessionSettings;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

final class ProtocolSessionCallbackCapabilityAndFramingTest extends ProtocolSessionContractSupport {

    private enum CallbackExit {
        RUNTIME_EXCEPTION,
        ERROR,
        TIMEOUT,
        CANCELLATION
    }

    private enum Abandonment {
        TIMEOUT,
        SESSION_CLOSE,
        CALLER_INTERRUPT
    }

    @Test
    void protocolWriterIsThreadConfinedAndExpiresWithItsAdapterInvocation() {
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        AtomicReference<ProtocolWriter> retained = new AtomicReference<>();
        AtomicBoolean firstWrite = new AtomicBoolean(true);
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                if (firstWrite.getAndSet(false)) {
                    retained.set(writer);
                    assertRejectedFromForeignThread(() -> writer.writeLine("foreign"));
                    assertRejectedFromForeignThread(() -> writer.write(new byte[] {0, 1}, 0, 1));
                    assertRejectedFromForeignThread(writer::remainingByteCapacity);
                    assertRejectedFromForeignThread(() -> writer.ensureByteCapacity(1));
                }
                writer.writeLine(request);
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "response";
            }
        };
        ControllableProcess process =
                new ControllableProcess(stdin, InputStream.nullInputStream(), InputStream.nullInputStream());

        try (DefaultProtocolSession<String, String> protocol =
                new DefaultProtocolSession<>(session(process), adapter, ProtocolSessionSettings.defaults())) {
            assertEquals("response", protocol.request("first"));

            assertThrows(IllegalStateException.class, () -> retained.get().writeLine("late"));
            assertThrows(IllegalStateException.class, retained.get()::remainingByteCapacity);
            assertThrows(IllegalStateException.class, () -> retained.get().ensureByteCapacity(1));
            assertEquals("response", protocol.request("second"));
            assertEquals("first\nsecond\n", stdin.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void protocolWriterPreflightsCapacityAndWritesArraySlicesWithoutChargingUnusedBytes() {
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                assertEquals(4, writer.remainingByteCapacity());
                writer.ensureByteCapacity(2);
                writer.write(new byte[] {9, 1, 2, 9}, 1, 2);
                assertEquals(2, writer.remainingByteCapacity());
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "response";
            }
        };
        ControllableProcess process =
                new ControllableProcess(stdin, InputStream.nullInputStream(), InputStream.nullInputStream());

        try (DefaultProtocolSession<String, String> protocol = new DefaultProtocolSession<>(
                session(process), adapter, ProtocolSessionSettings.defaults().withMaxRequestBytes(4))) {
            assertEquals("response", protocol.request("ignored"));
            assertArrayEquals(new byte[] {1, 2}, stdin.toByteArray());
        }
    }

    @Test
    void protocolWriterRejectsOversizedPreflightBeforeStdinMutation() throws Exception {
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.ensureByteCapacity(5);
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                throw new AssertionError("oversized request must not reach response decoding");
            }
        };
        ControllableProcess process =
                new ControllableProcess(stdin, InputStream.nullInputStream(), InputStream.nullInputStream());
        try (DefaultProtocolSession<String, String> protocol = new DefaultProtocolSession<>(
                session(process), adapter, ProtocolSessionSettings.defaults().withMaxRequestBytes(4))) {
            ProtocolSessionException failure =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("ignored"));

            assertEquals(ProtocolSessionException.Reason.REQUEST_TOO_LARGE, failure.reason());
            assertEquals(0, stdin.size());
            protocol.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
        }
    }

    @Test
    void protocolReaderIsThreadConfinedAndExpiresWithItsAdapterInvocation() {
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        AtomicReference<ProtocolReader> retained = new AtomicReference<>();
        AtomicBoolean firstRead = new AtomicBoolean(true);
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.writeLine(request);
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                ProtocolReader stdout = readers.stdout();
                if (firstRead.getAndSet(false)) {
                    retained.set(stdout);
                    assertRejectedFromForeignThread(() -> stdout.readLine(16));
                }
                return stdout.readLine(16);
            }
        };
        ControllableProcess process = new ControllableProcess(
                stdin,
                new ByteArrayInputStream("first\nsecond\n".getBytes(StandardCharsets.UTF_8)),
                InputStream.nullInputStream());

        try (DefaultProtocolSession<String, String> protocol =
                new DefaultProtocolSession<>(session(process), adapter, ProtocolSessionSettings.defaults())) {
            assertEquals("first", protocol.request("one"));

            assertThrows(IllegalStateException.class, () -> retained.get().readLine(16));
            assertEquals("second", protocol.request("two"));
            assertEquals("one\ntwo\n", stdin.toString(StandardCharsets.UTF_8));
        }
    }

    @Test
    void atomicDecoderSuffixSurvivesRequestReadersAndUsesEachRequestBudget() throws Exception {
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return readers.stdout().readLine(1);
            }
        };
        ControllableProcess process =
                new ControllableProcess(stdin, new ByteArrayInputStream(new byte[] {1}), InputStream.nullInputStream());
        ProtocolSessionSettings settings = options(new AtomicRequestLinesCharset())
                .withOutputBacklogLimit(8)
                .withMaxResponseBytes(1)
                .withMaxResponseChars(2);

        try (DefaultProtocolSession<String, String> protocol =
                new DefaultProtocolSession<>(session(process), adapter, settings)) {
            assertEquals("a", protocol.request("first"));
            assertEquals("b", protocol.request("second"));

            ProtocolSessionException eof =
                    assertThrows(ProtocolSessionException.class, () -> protocol.request("third"));
            assertEquals(ProtocolSessionException.Reason.EOF, eof.reason());
            protocol.onExit().get(1, TimeUnit.SECONDS);
            assertFalse(process.isAlive());
            assertEquals(0, stdin.size());
        }
    }

    @ParameterizedTest
    @EnumSource(CallbackExit.class)
    void escapedProtocolWriterExpiresAfterEveryCallbackExitBeforeStdinMutation(CallbackExit exit) throws Exception {
        assertEscapedProtocolCapabilityExpires(exit, true);
    }

    @ParameterizedTest
    @EnumSource(CallbackExit.class)
    void escapedProtocolReaderExpiresAfterEveryCallbackExitBeforeDecoderMutation(CallbackExit exit) throws Exception {
        assertEscapedProtocolCapabilityExpires(exit, false);
    }

    private static void assertEscapedProtocolCapabilityExpires(CallbackExit exit, boolean writerPhase)
            throws Exception {
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        AtomicReference<ProtocolWriter> retainedWriter = new AtomicReference<>();
        AtomicReference<ProtocolReader> retainedReader = new AtomicReference<>();
        AtomicReference<ProtocolReader> retainedStderr = new AtomicReference<>();
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        CountDownLatch callbackFinished = new CountDownLatch(1);
        RuntimeException runtimeFailure = new IllegalStateException("protocol callback runtime failure");
        AssertionError fatalFailure = new AssertionError("protocol callback fatal failure");
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                if (!writerPhase) {
                    writer.writeLine(request);
                    writer.flush();
                    return;
                }
                retainedWriter.set(writer);
                runEscapingCallback(
                        exit, callbackStarted, releaseCallback, callbackFinished, runtimeFailure, fatalFailure);
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                if (writerPhase) {
                    return "response";
                }
                retainedReader.set(readers.stdout());
                retainedStderr.set(readers.stderr());
                runEscapingCallback(
                        exit, callbackStarted, releaseCallback, callbackFinished, runtimeFailure, fatalFailure);
                return "response";
            }
        };
        ControllableProcess process = new ControllableProcess(
                stdin,
                new ByteArrayInputStream("queued\n".getBytes(StandardCharsets.UTF_8)),
                InputStream.nullInputStream());
        DefaultProtocolSession<String, String> protocol =
                new DefaultProtocolSession<>(session(process), adapter, ProtocolSessionSettings.defaults());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Thread> callerThread = new AtomicReference<>();
        AtomicBoolean callerInterruptRestored = new AtomicBoolean();
        try {
            Future<Throwable> outcome = executor.submit(() -> {
                callerThread.set(Thread.currentThread());
                Throwable failure = captureFailure(() -> protocol.request(
                        "request", exit == CallbackExit.TIMEOUT ? Duration.ofMillis(50) : Duration.ofDays(1)));
                callerInterruptRestored.set(Thread.currentThread().isInterrupted());
                return failure;
            });
            assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));
            if (exit == CallbackExit.CANCELLATION) {
                Objects.requireNonNull(callerThread.get(), "callerThread").interrupt();
            }

            Throwable requestFailure = outcome.get(2, TimeUnit.SECONDS);
            switch (exit) {
                case RUNTIME_EXCEPTION -> {
                    ProtocolSessionException typed = assertInstanceOf(ProtocolSessionException.class, requestFailure);
                    assertEquals(
                            writerPhase
                                    ? ProtocolSessionException.Reason.FAILURE
                                    : ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED,
                            typed.reason());
                    assertSame(runtimeFailure, typed.getCause());
                }
                case ERROR -> assertSame(fatalFailure, requestFailure);
                case TIMEOUT ->
                    assertEquals(
                            ProtocolSessionException.Reason.TIMEOUT,
                            assertInstanceOf(ProtocolSessionException.class, requestFailure)
                                    .reason());
                case CANCELLATION -> {
                    assertEquals(
                            ProtocolSessionException.Reason.FAILURE,
                            assertInstanceOf(ProtocolSessionException.class, requestFailure)
                                    .reason());
                    assertTrue(callerInterruptRestored.get());
                }
            }

            releaseCallback.countDown();
            assertTrue(callbackFinished.await(1, TimeUnit.SECONDS));
            byte[] stdinBeforeEscapedUse = stdin.toByteArray();
            if (writerPhase) {
                assertThrows(
                        IllegalStateException.class, () -> retainedWriter.get().writeLine("late"));
            } else {
                assertThrows(
                        IllegalStateException.class, () -> retainedReader.get().readLine(16));
                assertThrows(
                        IllegalStateException.class, () -> retainedStderr.get().readLine(16));
            }
            assertArrayEquals(stdinBeforeEscapedUse, stdin.toByteArray());
        } finally {
            releaseCallback.countDown();
            protocol.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private static void runEscapingCallback(
            CallbackExit exit,
            CountDownLatch callbackStarted,
            CountDownLatch releaseCallback,
            CountDownLatch callbackFinished,
            RuntimeException runtimeFailure,
            Error fatalFailure) {
        callbackStarted.countDown();
        try {
            switch (exit) {
                case RUNTIME_EXCEPTION -> throw runtimeFailure;
                case ERROR -> throw fatalFailure;
                case TIMEOUT, CANCELLATION -> awaitUninterruptibly(releaseCallback);
            }
        } finally {
            callbackFinished.countDown();
        }
    }

    @ParameterizedTest
    @EnumSource(Abandonment.class)
    void writerInterruptObservesExpiredCapabilityBeforeStdinMutation(Abandonment abandonment) throws Exception {
        assertInterruptObservesExpiredProtocolCapability(abandonment, true);
    }

    @ParameterizedTest
    @EnumSource(Abandonment.class)
    void readerInterruptObservesExpiredCapabilitiesBeforeOutputMutation(Abandonment abandonment) throws Exception {
        assertInterruptObservesExpiredProtocolCapability(abandonment, false);
    }

    private static void assertInterruptObservesExpiredProtocolCapability(Abandonment abandonment, boolean writerPhase)
            throws Exception {
        ByteArrayOutputStream stdin = new ByteArrayOutputStream();
        CountDownLatch callbackStarted = new CountDownLatch(1);
        CountDownLatch capabilityAttempted = new CountDownLatch(1);
        CountDownLatch releaseCallback = new CountDownLatch(1);
        AtomicReference<Throwable> firstCapabilityFailure = new AtomicReference<>();
        AtomicReference<Throwable> secondCapabilityFailure = new AtomicReference<>();
        AtomicInteger stdinBytesBeforeUse = new AtomicInteger(-1);
        AtomicInteger stdinBytesAfterUse = new AtomicInteger(-1);
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                if (!writerPhase) {
                    writer.writeLine(request);
                    writer.flush();
                    return;
                }
                awaitAbandonmentInterrupt(
                        () -> firstCapabilityFailure.set(captureFailure(() -> writer.writeLine("late"))),
                        callbackStarted,
                        capabilityAttempted,
                        releaseCallback,
                        stdin,
                        stdinBytesBeforeUse,
                        stdinBytesAfterUse);
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                if (writerPhase) {
                    return "response";
                }
                awaitAbandonmentInterrupt(
                        () -> {
                            firstCapabilityFailure.set(
                                    captureFailure(() -> readers.stdout().readLine(16)));
                            secondCapabilityFailure.set(
                                    captureFailure(() -> readers.stderr().readLine(16)));
                        },
                        callbackStarted,
                        capabilityAttempted,
                        releaseCallback,
                        stdin,
                        stdinBytesBeforeUse,
                        stdinBytesAfterUse);
                return "response";
            }
        };
        DefaultProtocolSession<String, String> protocol = new DefaultProtocolSession<>(
                session(new ControllableProcess(
                        stdin,
                        new ByteArrayInputStream("queued\n".getBytes(StandardCharsets.UTF_8)),
                        new ByteArrayInputStream("diagnostic\n".getBytes(StandardCharsets.UTF_8)))),
                adapter,
                ProtocolSessionSettings.defaults());
        ExecutorService executor = Executors.newSingleThreadExecutor();
        AtomicReference<Thread> callerThread = new AtomicReference<>();
        AtomicBoolean callerInterruptRestored = new AtomicBoolean();
        try {
            Future<Throwable> outcome = executor.submit(() -> {
                callerThread.set(Thread.currentThread());
                Throwable failure = captureFailure(() -> protocol.request(
                        "request", abandonment == Abandonment.TIMEOUT ? Duration.ofMillis(250) : Duration.ofDays(1)));
                callerInterruptRestored.set(Thread.currentThread().isInterrupted());
                return failure;
            });
            assertTrue(callbackStarted.await(1, TimeUnit.SECONDS));

            switch (abandonment) {
                case TIMEOUT -> {}
                case SESSION_CLOSE -> protocol.close();
                case CALLER_INTERRUPT ->
                    Objects.requireNonNull(callerThread.get(), "callerThread").interrupt();
            }

            assertTrue(capabilityAttempted.await(2, TimeUnit.SECONDS));
            assertInstanceOf(IllegalStateException.class, firstCapabilityFailure.get());
            if (!writerPhase) {
                assertInstanceOf(IllegalStateException.class, secondCapabilityFailure.get());
            }
            assertEquals(stdinBytesBeforeUse.get(), stdinBytesAfterUse.get());

            ProtocolSessionException requestFailure =
                    assertInstanceOf(ProtocolSessionException.class, outcome.get(2, TimeUnit.SECONDS));
            assertEquals(
                    switch (abandonment) {
                        case TIMEOUT -> ProtocolSessionException.Reason.TIMEOUT;
                        case SESSION_CLOSE -> ProtocolSessionException.Reason.CLOSED;
                        case CALLER_INTERRUPT -> ProtocolSessionException.Reason.FAILURE;
                    },
                    requestFailure.reason());
            if (abandonment == Abandonment.SESSION_CLOSE) {
                assertInstanceOf(BoundedTaskRunner.TaskCancelledException.class, requestFailure.getCause());
            }
            assertEquals(abandonment == Abandonment.CALLER_INTERRUPT, callerInterruptRestored.get());
        } finally {
            releaseCallback.countDown();
            protocol.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    private static void awaitAbandonmentInterrupt(
            Runnable capabilityUse,
            CountDownLatch callbackStarted,
            CountDownLatch capabilityAttempted,
            CountDownLatch releaseCallback,
            ByteArrayOutputStream stdin,
            AtomicInteger stdinBytesBeforeUse,
            AtomicInteger stdinBytesAfterUse) {
        callbackStarted.countDown();
        try {
            new CountDownLatch(1).await();
            throw new AssertionError("protocol callback was not interrupted after abandonment");
        } catch (InterruptedException expected) {
            stdinBytesBeforeUse.set(stdin.size());
            capabilityUse.run();
            stdinBytesAfterUse.set(stdin.size());
            capabilityAttempted.countDown();
            awaitUninterruptibly(releaseCallback);
        }
    }

    private static final class AtomicRequestLinesCharset extends Charset {

        private AtomicRequestLinesCharset() {
            super("X-Procwright-Atomic-Request-Lines", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 4) {
                private boolean emitted;

                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (!input.hasRemaining() || emitted) {
                        return CoderResult.UNDERFLOW;
                    }
                    if (output.remaining() < 4) {
                        return CoderResult.OVERFLOW;
                    }
                    input.get();
                    output.put("a\nb\n");
                    emitted = true;
                    return CoderResult.UNDERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static void assertRejectedFromForeignThread(Runnable operation) {
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread foreign =
                new Thread(() -> failure.set(captureFailure(operation)), "procwright-protocol-capability-scope-test");
        foreign.setDaemon(true);
        foreign.start();
        try {
            foreign.join(TimeUnit.SECONDS.toMillis(1));
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Interrupted while joining the capability-scope test", exception);
        }
        assertFalse(foreign.isAlive());
        assertInstanceOf(IllegalStateException.class, failure.get());
    }
}
