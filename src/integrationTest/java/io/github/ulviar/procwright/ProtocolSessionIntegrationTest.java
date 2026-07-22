/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.internal.session.PoolTestAccess;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.PooledProtocolSessionException;
import io.github.ulviar.procwright.session.PooledProtocolSessionMetrics;
import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;

final class ProtocolSessionIntegrationTest {

    @Test
    void protocolSessionSupportsMultilineStringRequests() {
        try (ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(), new FramedStringAdapter(), call -> call.withArgs("length-line-frame"))) {
            String response = session.request("one\ntwo", Duration.ofSeconds(2));

            assertEquals("one\ntwo", response);
        }
    }

    @Test
    void readinessProbeRunsBeforeProtocolSessionIsReturned() {
        try (ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(), new FramedStringAdapter(), call -> call.withArgs("length-line-frame")
                        .withReadiness(ready -> assertEquals("ready", ready.request("ready")))
                        .withReadinessTimeout(Duration.ofSeconds(2)))) {
            assertEquals("payload", session.request("payload"));
        }
    }

    @Test
    void requestSizeLimitIsTypedFailure() {
        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> openProtocolSession(
                        fixtureService(), new FramedStringAdapter(), call -> call.withArgs("length-line-frame")
                                .withMaxRequestBytes(4))
                .request("too-large"));

        assertEquals(ProtocolSessionException.Reason.REQUEST_TOO_LARGE, exception.reason());
    }

    @Test
    void requestCharacterLimitIsTypedFailure() {
        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> openProtocolSession(
                        fixtureService(), new TextLineAdapter(), call -> call.withArgs("controlled-line-repl")
                                .withMaxRequestChars(4))
                .request("hello"));

        assertEquals(ProtocolSessionException.Reason.REQUEST_TOO_LARGE, exception.reason());
    }

    @Test
    void protocolAdapterCannotSwallowRequestLimitFailure() throws Exception {
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                try {
                    writer.write(request);
                } catch (ProtocolSessionException ignored) {
                    // Returning normally must not clear the writer's first typed failure.
                }
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "fallback";
            }
        };
        ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), adapter, call -> call.withArgs("ignore-stdin", "--millis=5000")
                        .withMaxRequestBytes(1));
        try {
            ProtocolSessionException exception =
                    assertThrows(ProtocolSessionException.class, () -> session.request("too-large"));

            assertEquals(ProtocolSessionException.Reason.REQUEST_TOO_LARGE, exception.reason());
            session.onExit().get(2, TimeUnit.SECONDS);
            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> session.request("x"));
            assertEquals(ProtocolSessionException.Reason.REQUEST_TOO_LARGE, followUp.reason());
        } finally {
            session.close();
        }
    }

    @Test
    void caughtWriterFailurePrecedesSecondaryRuntimeException() throws Exception {
        IllegalStateException secondaryFailure = new IllegalStateException("secondary writer failure");
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                try {
                    writer.write(request);
                } catch (ProtocolSessionException ignored) {
                    throw secondaryFailure;
                }
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "fallback";
            }
        };
        ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), adapter, call -> call.withArgs("ignore-stdin", "--millis=5000")
                        .withMaxRequestBytes(1));
        try {
            ProtocolSessionException failure =
                    assertThrows(ProtocolSessionException.class, () -> session.request("too-large"));

            assertEquals(ProtocolSessionException.Reason.REQUEST_TOO_LARGE, failure.reason());
            session.onExit().get(2, TimeUnit.SECONDS);
            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> session.request("x"));
            assertEquals(ProtocolSessionException.Reason.REQUEST_TOO_LARGE, followUp.reason());
        } finally {
            session.close();
        }
    }

    @Test
    void caughtWriterFailurePrecedesSecondaryErrorButRethrowsThatError() throws Exception {
        AssertionError secondaryFailure = new AssertionError("secondary writer error");
        AtomicReference<ProtocolSessionException> caughtFailure = new AtomicReference<>();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                try {
                    writer.write(request);
                } catch (ProtocolSessionException failure) {
                    caughtFailure.set(failure);
                    throw secondaryFailure;
                }
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "fallback";
            }
        };
        ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), adapter, call -> call.withArgs("ignore-stdin", "--millis=5000")
                        .withMaxRequestBytes(1));
        try {
            AssertionError thrown = assertThrows(AssertionError.class, () -> session.request("too-large"));

            assertSame(secondaryFailure, thrown);
            ProtocolSessionException requestLimit = caughtFailure.get();
            assertEquals(ProtocolSessionException.Reason.REQUEST_TOO_LARGE, requestLimit.reason());
            assertIdentitySuppressedOnce(secondaryFailure, requestLimit);
            session.onExit().get(2, TimeUnit.SECONDS);
            AssertionError followUp = assertThrows(AssertionError.class, () -> session.request("x"));
            assertSame(secondaryFailure, followUp);
        } finally {
            session.close();
        }
    }

    @Test
    void adapterIllegalStateExceptionIsNotMisclassifiedAsClosed() throws Exception {
        IllegalStateException adapterFailure = new IllegalStateException("adapter writer failed");
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                throw adapterFailure;
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "unused";
            }
        };
        ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), adapter, call -> call.withArgs("ignore-stdin", "--millis=5000"));
        try {
            ProtocolSessionException failure =
                    assertThrows(ProtocolSessionException.class, () -> session.request("ignored"));

            assertEquals(ProtocolSessionException.Reason.FAILURE, failure.reason());
            assertSame(adapterFailure, failure.getCause());
            session.onExit().get(2, TimeUnit.SECONDS);
        } finally {
            session.close();
        }
    }

    @Test
    void caughtRequestLimitAfterPartialWriteStillTerminatesSession() throws Exception {
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.write("a");
                try {
                    writer.write("bc");
                } catch (ProtocolSessionException ignored) {
                    // The first byte was already written, but this request cannot become successful.
                }
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return "fallback";
            }
        };
        ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), adapter, call -> call.withArgs("ignore-stdin", "--millis=5000")
                        .withMaxRequestBytes(2));
        try {
            ProtocolSessionException exception =
                    assertThrows(ProtocolSessionException.class, () -> session.request("ignored"));

            assertEquals(ProtocolSessionException.Reason.REQUEST_TOO_LARGE, exception.reason());
            session.onExit().get(2, TimeUnit.SECONDS);
        } finally {
            session.close();
        }
    }

    @Test
    void protocolTextWriteUsesOneEncoderForValidationAndBytes() {
        AlternatingEncoderCharset charset = new AlternatingEncoderCharset();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.writeLine(request);
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return readers.stdout().readLine(32);
            }
        };

        try (ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), adapter, call -> call.withArgs("controlled-line-repl")
                        .withMaxRequestBytes(2)
                        .withCharsetPolicy(CharsetPolicy.replace(charset)))) {
            assertEquals("response:x", session.request("x"));
            assertEquals(1, charset.encoderCreations());
        }
    }

    @Test
    void responseSizeLimitIsTypedFailure() {
        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> openProtocolSession(
                        fixtureService(), new FramedStringAdapter(), call -> call.withArgs("length-line-frame")
                                .withMaxResponseBytes(8))
                .request("response is too large"));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
    }

    @Test
    void protocolAdapterCannotSwallowResponseLimitFailure() throws Exception {
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                try {
                    readers.stdout().readExactly(2);
                    return "unexpected";
                } catch (ProtocolSessionException ignored) {
                    return "fallback";
                }
            }
        };
        ProtocolSession<String, String> session = openProtocolSession(fixtureService(), adapter, call -> call.withArgs(
                        "partial", "--stdout=ab", "--stderr=", "--hold-millis=5000")
                .withMaxResponseBytes(1));
        try {
            ProtocolSessionException exception =
                    assertThrows(ProtocolSessionException.class, () -> session.request(""));

            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
            session.onExit().get(2, TimeUnit.SECONDS);
            ProtocolSessionException followUp = assertThrows(ProtocolSessionException.class, () -> session.request(""));
            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, followUp.reason());
        } finally {
            session.close();
        }
    }

    @Test
    void caughtProtocolLineReaderFailurePrecedesSecondaryRuntimeException() throws Exception {
        IllegalArgumentException secondaryFailure = new IllegalArgumentException("secondary line reader failure");
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                try {
                    return readers.stdout().readLine(1);
                } catch (ProtocolSessionException ignored) {
                    throw secondaryFailure;
                }
            }
        };
        ProtocolSession<String, String> session = openProtocolSession(fixtureService(), adapter, call -> call.withArgs(
                        "partial", "--stdout=ab\n", "--stderr=", "--hold-millis=5000")
                .withMaxResponseChars(1));
        try {
            ProtocolSessionException failure = assertThrows(ProtocolSessionException.class, () -> session.request(""));

            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, failure.reason());
            session.onExit().get(2, TimeUnit.SECONDS);
            ProtocolSessionException followUp = assertThrows(ProtocolSessionException.class, () -> session.request(""));
            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, followUp.reason());
        } finally {
            session.close();
        }
    }

    @Test
    void caughtProtocolLineReaderFailurePrecedesSecondaryErrorButRethrowsThatError() throws Exception {
        AssertionError secondaryFailure = new AssertionError("secondary line reader error");
        AtomicReference<ProtocolSessionException> caughtFailure = new AtomicReference<>();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                try {
                    return readers.stdout().readLine(1);
                } catch (ProtocolSessionException failure) {
                    caughtFailure.set(failure);
                    throw secondaryFailure;
                }
            }
        };
        ProtocolSession<String, String> session = openProtocolSession(fixtureService(), adapter, call -> call.withArgs(
                        "partial", "--stdout=ab\n", "--stderr=", "--hold-millis=5000")
                .withMaxResponseChars(1));
        try {
            AssertionError thrown = assertThrows(AssertionError.class, () -> session.request(""));

            assertSame(secondaryFailure, thrown);
            ProtocolSessionException responseLimit = caughtFailure.get();
            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, responseLimit.reason());
            assertIdentitySuppressedOnce(secondaryFailure, responseLimit);
            session.onExit().get(2, TimeUnit.SECONDS);
            AssertionError followUp = assertThrows(AssertionError.class, () -> session.request(""));
            assertSame(secondaryFailure, followUp);
        } finally {
            session.close();
        }
    }

    @Test
    void caughtProtocolByteReaderFailurePrecedesSecondaryRuntimeException() throws Exception {
        IllegalArgumentException secondaryFailure = new IllegalArgumentException("secondary byte reader failure");
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                try {
                    readers.stdout().readExactly(2);
                    return "unexpected";
                } catch (ProtocolSessionException ignored) {
                    throw secondaryFailure;
                }
            }
        };
        ProtocolSession<String, String> session = openProtocolSession(fixtureService(), adapter, call -> call.withArgs(
                        "partial", "--stdout=ab", "--stderr=", "--hold-millis=5000")
                .withMaxResponseBytes(1));
        try {
            ProtocolSessionException failure = assertThrows(ProtocolSessionException.class, () -> session.request(""));

            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, failure.reason());
            session.onExit().get(2, TimeUnit.SECONDS);
            ProtocolSessionException followUp = assertThrows(ProtocolSessionException.class, () -> session.request(""));
            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, followUp.reason());
        } finally {
            session.close();
        }
    }

    @Test
    void caughtProtocolByteReaderFailurePrecedesSecondaryErrorButRethrowsThatError() throws Exception {
        AssertionError secondaryFailure = new AssertionError("secondary byte reader error");
        AtomicReference<ProtocolSessionException> caughtFailure = new AtomicReference<>();
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                try {
                    readers.stdout().readExactly(2);
                    return "unexpected";
                } catch (ProtocolSessionException failure) {
                    caughtFailure.set(failure);
                    throw secondaryFailure;
                }
            }
        };
        ProtocolSession<String, String> session = openProtocolSession(fixtureService(), adapter, call -> call.withArgs(
                        "partial", "--stdout=ab", "--stderr=", "--hold-millis=5000")
                .withMaxResponseBytes(1));
        try {
            AssertionError thrown = assertThrows(AssertionError.class, () -> session.request(""));

            assertSame(secondaryFailure, thrown);
            ProtocolSessionException responseLimit = caughtFailure.get();
            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, responseLimit.reason());
            assertIdentitySuppressedOnce(secondaryFailure, responseLimit);
            session.onExit().get(2, TimeUnit.SECONDS);
            AssertionError followUp = assertThrows(AssertionError.class, () -> session.request(""));
            assertSame(secondaryFailure, followUp);
        } finally {
            session.close();
        }
    }

    @Test
    void protocolAdapterCannotRetryPastCumulativeByteBudget() throws Exception {
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                ProtocolReader stdout = readers.stdout();
                assertEquals((byte) 'a', stdout.readByte());
                for (int attempt = 0; attempt < 2; attempt++) {
                    try {
                        stdout.readByte();
                        throw new AssertionError("response budget retry unexpectedly consumed a byte");
                    } catch (ProtocolSessionException exception) {
                        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
                    }
                }
                return "fallback";
            }
        };
        ProtocolSession<String, String> session = openProtocolSession(fixtureService(), adapter, call -> call.withArgs(
                        "partial", "--stdout=abc", "--stderr=", "--hold-millis=5000")
                .withMaxResponseBytes(1));
        try {
            ProtocolSessionException exception =
                    assertThrows(ProtocolSessionException.class, () -> session.request(""));

            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
            session.onExit().get(2, TimeUnit.SECONDS);
        } finally {
            session.close();
        }
    }

    @Test
    void strictCharsetPolicyReportsMalformedOutput() {
        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> openProtocolSession(
                        fixtureService(), new FramedBytesAsLineAdapter(), call -> call.withArgs("length-line-frame")
                                .withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8)))
                .request(new byte[] {(byte) 0xFF}));

        assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, exception.reason());
        assertEquals(true, exception.transcript().malformed());
    }

    @Test
    void strictExactTextFieldFailurePreservesTranscriptAndTerminatesSession() throws Exception {
        ProtocolSession<byte[], String> session = openProtocolSession(
                fixtureService(), new ExactTextFieldAdapter(), call -> call.withArgs("length-line-frame")
                        .withTranscriptLimit(64)
                        .withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8)));
        try {
            ProtocolSessionException exception =
                    assertThrows(ProtocolSessionException.class, () -> session.request(new byte[] {(byte) 0xff}));

            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, exception.reason());
            assertTrue(exception.transcript().text().contains("len:1"));
            assertTrue(exception.transcript().malformed());
            session.onExit().get(2, TimeUnit.SECONDS);
            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> session.request(new byte[] {'x'}));
            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, followUp.reason());
            assertTrue(followUp.transcript().text().contains("len:1"));
        } finally {
            session.close();
        }
    }

    @Test
    void caughtExactFieldLimitFailureStillTerminatesSession() throws Exception {
        ProtocolAdapter<byte[], String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(byte[] request, ProtocolWriter writer) {
                writer.writeLine(Integer.toString(request.length));
                writer.write(request);
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                ProtocolReader stdout = readers.stdout();
                int length = parseLength(stdout.readLine(32));
                ProtocolSessionException failure =
                        assertThrows(ProtocolSessionException.class, () -> stdout.readTextExactly(length, 1));
                assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, failure.reason());
                return "fallback";
            }
        };
        ProtocolSession<byte[], String> session =
                openProtocolSession(fixtureService(), adapter, call -> call.withArgs("length-line-frame"));
        try {
            ProtocolSessionException failure =
                    assertThrows(ProtocolSessionException.class, () -> session.request(new byte[] {'a', 'b'}));

            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, failure.reason());
            session.onExit().get(2, TimeUnit.SECONDS);
            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> session.request(new byte[] {'x'}));
            assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, followUp.reason());
        } finally {
            session.close();
        }
    }

    @Test
    void pendingContinuousInputMakesExactFieldSwitchATerminalProtocolFailure() throws Exception {
        ProtocolAdapter<byte[], String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(byte[] request, ProtocolWriter writer) {
                writer.writeLine(Integer.toString(request.length));
                writer.write(request);
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                ProtocolReader stdout = readers.stdout();
                parseLength(stdout.readLine(32));
                assertEquals("", stdout.readTextUntil((byte) 0xc3, 1));
                return stdout.readTextExactly(1, 1);
            }
        };
        ProtocolSession<byte[], String> session =
                openProtocolSession(fixtureService(), adapter, call -> call.withArgs("length-line-frame")
                        .withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8)));
        try {
            ProtocolSessionException exception = assertThrows(
                    ProtocolSessionException.class, () -> session.request(new byte[] {(byte) 0xc3, 'X', (byte) 0xa9}));

            assertEquals(ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED, exception.reason());
            session.onExit().get(2, TimeUnit.SECONDS);
            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> session.request(new byte[] {'x'}));
            assertEquals(ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED, followUp.reason());
        } finally {
            session.close();
        }
    }

    @Test
    void protocolAdapterCannotSwallowPersistentDecodeFailure() throws Exception {
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                try {
                    return readers.stdout().readLine(16);
                } catch (ProtocolSessionException ignored) {
                    return "fallback";
                }
            }
        };
        ProtocolSession<String, String> session = openProtocolSession(fixtureService(), adapter, call -> call.withArgs(
                        "partial", "--stdout=x", "--stderr=", "--hold-millis=5000")
                .withTranscriptLimit(32)
                .withCharsetPolicy(CharsetPolicy.replace(new PersistentResponseInvalidReplacementCharset())));
        try {
            ProtocolSessionException exception =
                    assertThrows(ProtocolSessionException.class, () -> session.request(""));

            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, exception.reason());
            assertTrue(exception.transcript().text().length() <= 32);
            session.onExit().get(2, TimeUnit.SECONDS);
            ProtocolSessionException followUp = assertThrows(ProtocolSessionException.class, () -> session.request(""));
            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, followUp.reason());
        } finally {
            session.close();
        }
    }

    @Test
    void concurrentTranscriptFailureWinsBeforeProtocolCallbackSuccess() throws Exception {
        CountDownLatch beforeFailure = new CountDownLatch(1);
        AtomicReference<ProtocolSession<String, String>> sessionReference = new AtomicReference<>();
        PersistentTranscriptRuntimeFailureCharset charset =
                new PersistentTranscriptRuntimeFailureCharset(beforeFailure);
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.writeLine(request);
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                awaitIgnoringInterrupts(beforeFailure);
                sessionReference.get().onExit().join();
                return "fallback";
            }
        };
        ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), adapter, call -> call.withArgs("controlled-line-repl")
                        .withTranscriptLimit(32)
                        .withCharsetPolicy(CharsetPolicy.report(charset)));
        sessionReference.set(session);
        try {
            ProtocolSessionException exception =
                    assertThrows(ProtocolSessionException.class, () -> session.request("trigger"));

            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, exception.reason());
            assertTrue(causeChainContains(exception, charset.failure()));
            session.onExit().get(2, TimeUnit.SECONDS);
        } finally {
            session.close();
        }
    }

    @Test
    void transcriptDecoderInitializationFailureClosesProtocolSessionForEitherStream() throws Exception {
        for (int failingCreation : List.of(1, 2)) {
            PersistentTranscriptNewDecoderFailureCharset charset =
                    new PersistentTranscriptNewDecoderFailureCharset(failingCreation);

            ProtocolSessionException exception = assertThrows(
                    ProtocolSessionException.class,
                    () -> openProtocolSession(fixtureService(), new StdoutLineAdapter(16), call -> call.withArgs(
                                    "partial", "--stdout=", "--stderr=", "--hold-millis=5000")
                            .withTranscriptLimit(32)
                            .withCharsetPolicy(CharsetPolicy.report(charset))));

            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, exception.reason());
            assertTrue(causeChainContains(exception, charset.failure()));
            assertTrue(exception.transcript().text().length() <= 32);
            assertEquals(failingCreation, charset.decoderCreations());
        }
    }

    @Test
    void transcriptDecoderFlushRuntimeFailureClosesProtocolSession() throws Exception {
        PersistentTranscriptFlushFailureCharset charset = new PersistentTranscriptFlushFailureCharset();
        ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), new StdoutLineAdapter(16), call -> call.withArgs(
                                "partial", "--stdout=x", "--stderr=", "--hold-millis=100")
                        .withTranscriptLimit(32)
                        .withCharsetPolicy(CharsetPolicy.report(charset)));
        try {
            ProtocolSessionException exception =
                    assertThrows(ProtocolSessionException.class, () -> session.request(""));

            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, exception.reason());
            assertTrue(causeChainContains(exception, charset.failure()));
            assertTrue(exception.transcript().text().length() <= 32);
            session.onExit().get(2, TimeUnit.SECONDS);
        } finally {
            session.close();
        }
    }

    @Test
    void textCharacterLimitFailsBeforeDelimiterOrEof() {
        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> openProtocolSession(
                        fixtureService(),
                        new StdoutLineAdapter(4),
                        call -> call.withArgs("burst", "--stdout-bytes=100", "--stdout-byte=a"))
                .request(""));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
    }

    @Test
    void textCharacterLimitAppliesAcrossMultipleTextReads() {
        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> openProtocolSession(
                        fixtureService(), new TwoLineTextAdapter(), call -> call.withArgs("controlled-line-repl")
                                .withMaxResponseChars(20))
                .request("multi"));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
    }

    @Test
    void stdoutBacklogOverflowIsVisibleWhenAdapterReadsOtherStream() {
        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> openProtocolSession(
                        fixtureService(), new StderrLineAdapter(16), call -> call.withArgs(
                                        "partial", "--stdout=" + "o".repeat(4096), "--stderr=", "--hold-millis=5000")
                                .withOutputBacklogLimit(128))
                .request(""));

        assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, exception.reason());
    }

    @Test
    void requestAfterStdoutBacklogOverflowReportsOverflowReason() {
        try (ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), new StderrLineAdapter(16), call -> call.withArgs(
                                "partial", "--stdout=" + "o".repeat(4096), "--stderr=", "--hold-millis=5000")
                        .withOutputBacklogLimit(128))) {
            ProtocolSessionException overflow = assertThrows(ProtocolSessionException.class, () -> session.request(""));
            assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, overflow.reason());

            ProtocolSessionException followUp = assertThrows(ProtocolSessionException.class, () -> session.request(""));

            assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, followUp.reason());
            assertTrue(followUp.getMessage().contains("closed by an earlier failure"));
        }
    }

    @Test
    void stderrBacklogOverflowFailsOnlyWhenReadAndNeverExposesParseableSuffix() throws Exception {
        String parseableSuffix = "plausible-response\n";
        String stderr = "e".repeat(4096) + parseableSuffix;
        ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), new StderrLineAdapter(64), call -> call.withArgs(
                                "partial", "--stdout=", "--stderr=" + stderr, "--hold-millis=5000")
                        .withOutputBacklogLimit(64)
                        .withTranscriptLimit(8192));
        try {
            ProtocolSessionException overflow = assertThrows(ProtocolSessionException.class, () -> session.request(""));

            assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, overflow.reason());
            assertTrue(overflow.transcript().text().contains(parseableSuffix));
            session.onExit().get(2, TimeUnit.SECONDS);

            ProtocolSessionException followUp = assertThrows(ProtocolSessionException.class, () -> session.request(""));
            assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, followUp.reason());
            assertTrue(followUp.getMessage().contains("closed by an earlier failure"));
            assertTrue(followUp.exitCode().isPresent());
        } finally {
            session.close();
        }
    }

    @Test
    void unreadChattyStderrDoesNotKillLongLivedProtocolSession() {
        try (ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(), new TextLineAdapter(), call -> call.withArgs("controlled-line-repl")
                        .withOutputBacklogLimit(1024))) {
            for (int request = 0; request < 5; request++) {
                assertEquals(
                        "response:stderr-burst",
                        session.request("stderr-burst", Duration.ofSeconds(5)),
                        "request " + request + " must survive unread stderr beyond the backlog limit");
            }
        }
    }

    @Test
    void unreadStderrOverflowRemainsNonfatalAfterSuccessfulResponseAndProcessExit() throws Exception {
        try (ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), new StdoutLineAdapter(16), call -> call.withArgs(
                                "partial", "--stdout=ok\n", "--stderr=" + "e".repeat(4096), "--hold-millis=0")
                        .withOutputBacklogLimit(64))) {
            assertEquals("ok", session.request("", Duration.ofSeconds(2)));
            session.onExit().get(2, TimeUnit.SECONDS);

            ProtocolSessionException followUp = assertThrows(ProtocolSessionException.class, () -> session.request(""));
            assertEquals(ProtocolSessionException.Reason.PROCESS_EXITED, followUp.reason());
        }
    }

    @Test
    void stderrOverflowMarkerSurvivesProcessExitUntilItsFirstRead() throws Exception {
        CountDownLatch decoderEntered = new CountDownLatch(1);
        CountDownLatch allowStderrRead = new CountDownLatch(1);
        ProtocolAdapter<String, String> adapter = new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                decoderEntered.countDown();
                awaitIgnoringInterrupts(allowStderrRead);
                return readers.stderr().readLine(16);
            }
        };
        ProtocolSession<String, String> session = openProtocolSession(fixtureService(), adapter, call -> call.withArgs(
                        "partial", "--stdout=", "--stderr=" + "e".repeat(4096), "--hold-millis=0")
                .withOutputBacklogLimit(64));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<ProtocolSessionException> request = executor.submit(() ->
                    assertThrows(ProtocolSessionException.class, () -> session.request("", Duration.ofSeconds(5))));
            assertTrue(decoderEntered.await(1, TimeUnit.SECONDS));
            session.onExit().get(2, TimeUnit.SECONDS);

            allowStderrRead.countDown();
            ProtocolSessionException overflow = request.get(2, TimeUnit.SECONDS);

            assertEquals(ProtocolSessionException.Reason.OUTPUT_BACKLOG_OVERFLOW, overflow.reason());
            assertTrue(overflow.exitCode().isPresent());
        } finally {
            allowStderrRead.countDown();
            session.close();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void stderrStaysReadableThroughBoundedQueue() {
        try (ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(), new StderrEchoAdapter(), call -> call.withArgs("controlled-line-repl")
                        .withOutputBacklogLimit(1024))) {
            assertEquals("ping", session.request("ping", Duration.ofSeconds(2)));
        }
    }

    @Test
    void requestAgainstExitedProcessReportsProcessExited() throws Exception {
        try (ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(), new TextLineAdapter(), call -> call.withArgs("exit", "--stdout=gone"))) {
            session.onExit().get(2, java.util.concurrent.TimeUnit.SECONDS);

            ProtocolSessionException exception =
                    assertThrows(ProtocolSessionException.class, () -> session.request("hello", Duration.ofSeconds(1)));

            assertEquals(ProtocolSessionException.Reason.PROCESS_EXITED, exception.reason());
        }
    }

    @Test
    void delimiterReadStopsAtResponseByteLimitAndKeepsTranscriptBounded() {
        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> openProtocolSession(
                        fixtureService(), new DelimiterBytesAdapter(), call -> call.withArgs(
                                        "burst", "--stdout-bytes=256k", "--stdout-byte=a")
                                .withMaxResponseBytes(4096)
                                .withTranscriptLimit(128))
                .request(""));

        assertEquals(ProtocolSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
        assertTrue(exception.transcript().truncated());
        assertTrue(exception.transcript().text().length() <= 128);
    }

    @Test
    void illegalArgumentExceptionFromProtocolAdapterRemainsProtocolDecoderFailure() {
        IllegalArgumentException adapterFailure = new IllegalArgumentException("bad response");
        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> openProtocolSession(
                        fixtureService(),
                        new FailingDecoderAdapter(adapterFailure),
                        call -> call.withArgs("exit", "--stdout=ignored"))
                .request(""));

        assertEquals(ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED, exception.reason());
        assertSame(adapterFailure, exception.getCause());
    }

    @Test
    void nullProtocolResponseIsATerminalDecoderFailure() throws Exception {
        ProtocolAdapter<String, String> adapter = new FramedStringAdapter() {
            @Override
            public String readResponse(ProtocolReaders readers) {
                super.readResponse(readers);
                return null;
            }
        };

        try (ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), adapter, call -> call.withArgs("length-line-frame")
                        .withTranscriptLimit(8))) {
            ProtocolSessionException exception = assertThrows(
                    ProtocolSessionException.class, () -> session.request("0123456789abcdef0123456789abcdef"));

            assertEquals(ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED, exception.reason());
            assertInstanceOf(NullPointerException.class, exception.getCause());
            assertEquals(
                    "Protocol response decoder returned null",
                    exception.getCause().getMessage());
            assertTrue(exception.transcript().truncated());
            assertTrue(exception.transcript().text().length() <= 8);
            session.onExit().get(2, TimeUnit.SECONDS);
            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> session.request("again"));
            assertEquals(ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED, followUp.reason());
        }
    }

    @Test
    void decoderErrorClosesSessionAfterConsumingResponse() throws Exception {
        AssertionError decoderError = new AssertionError("decoder failed");
        ProtocolAdapter<String, String> adapter = new TextLineAdapter() {
            @Override
            public String readResponse(ProtocolReaders readers) {
                super.readResponse(readers);
                throw decoderError;
            }
        };

        try (ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), adapter, call -> call.withArgs("controlled-line-repl"))) {
            AssertionError thrown =
                    assertThrows(AssertionError.class, () -> session.request("hello", Duration.ofSeconds(1)));

            assertSame(decoderError, thrown);
            session.onExit().get(2, TimeUnit.SECONDS);
            AssertionError followUp =
                    assertThrows(AssertionError.class, () -> session.request("again", Duration.ofSeconds(1)));
            assertSame(decoderError, followUp);
        }
    }

    @Test
    void writerErrorClosesSessionAndPreservesError() throws Exception {
        AssertionError writerError = new AssertionError("writer failed");
        ProtocolAdapter<String, String> adapter = new TextLineAdapter() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                throw writerError;
            }
        };

        try (ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), adapter, call -> call.withArgs("controlled-line-repl"))) {
            AssertionError thrown =
                    assertThrows(AssertionError.class, () -> session.request("hello", Duration.ofSeconds(1)));

            assertSame(writerError, thrown);
            session.onExit().get(2, TimeUnit.SECONDS);
            AssertionError followUp =
                    assertThrows(AssertionError.class, () -> session.request("again", Duration.ofSeconds(1)));
            assertSame(writerError, followUp);
        }
    }

    @RepeatedTest(20)
    void processExitBeforeResponseIsTypedProtocolFailure() {
        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> openProtocolSession(
                        fixtureService(), new StdoutLineAdapter(16), call -> call.withArgs("exit"))
                .request(""));

        assertEquals(ProtocolSessionException.Reason.PROCESS_EXITED, exception.reason());
    }

    @Test
    void protocolRequestTimeoutClosesProcessAndPreservesTerminalReason() throws Exception {
        ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(),
                new StdoutLineAdapter(16),
                call -> call.withArgs("ignore-stdin", "--millis=5000", "--started=false"));
        try {
            ProtocolSessionException timeout =
                    assertThrows(ProtocolSessionException.class, () -> session.request("", Duration.ofMillis(100)));

            assertEquals(ProtocolSessionException.Reason.TIMEOUT, timeout.reason());
            session.onExit().get(2, TimeUnit.SECONDS);
            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> session.request("again", Duration.ofSeconds(1)));
            assertEquals(ProtocolSessionException.Reason.TIMEOUT, followUp.reason());
            assertTrue(followUp.getMessage().contains("closed by an earlier failure"));
        } finally {
            session.close();
        }
    }

    @Test
    void transcriptDecoderWithoutProgressFailsAndClosesProtocolSession() throws Exception {
        ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), new StdoutLineAdapter(16), call -> call.withArgs(
                                "partial", "--stdout=", "--stderr=" + "e".repeat(4096), "--hold-millis=5000")
                        .withTranscriptLimit(32)
                        .withCharsetPolicy(CharsetPolicy.report(new NoProgressCharset())));
        try {
            ProtocolSessionException exception =
                    assertThrows(ProtocolSessionException.class, () -> session.request("", Duration.ofSeconds(2)));

            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, exception.reason());
            assertTrue(exception.transcript().text().length() <= 32);
            session.onExit().get(2, TimeUnit.SECONDS);
            ProtocolSessionException followUp = assertThrows(ProtocolSessionException.class, () -> session.request(""));
            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, followUp.reason());
        } finally {
            session.close();
        }
    }

    @Test
    void invalidReplacementLengthInTranscriptFailsAndClosesProtocolSession() throws Exception {
        ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), new StdoutLineAdapter(16), call -> call.withArgs(
                                "partial", "--stdout=", "--stderr=x", "--hold-millis=5000")
                        .withTranscriptLimit(32)
                        .withCharsetPolicy(CharsetPolicy.report(new FiniteErrorAfterExhaustionCharset())));
        try {
            ProtocolSessionException exception =
                    assertThrows(ProtocolSessionException.class, () -> session.request("", Duration.ofSeconds(2)));

            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, exception.reason());
            assertTrue(exception.transcript().text().length() <= 32);
            session.onExit().get(2, TimeUnit.SECONDS);
            ProtocolSessionException followUp = assertThrows(ProtocolSessionException.class, () -> session.request(""));
            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, followUp.reason());
        } finally {
            session.close();
        }
    }

    @Test
    void persistentTextDecoderContractViolationIsDecodeErrorAndClosesSession() throws Exception {
        ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), new StdoutLineAdapter(16), call -> call.withArgs(
                                "partial", "--stdout=x", "--stderr=", "--hold-millis=5000")
                        .withTranscriptLimit(32)
                        .withCharsetPolicy(CharsetPolicy.replace(new PersistentResponseInvalidReplacementCharset())));
        try {
            ProtocolSessionException exception =
                    assertThrows(ProtocolSessionException.class, () -> session.request("", Duration.ofSeconds(2)));

            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, exception.reason());
            assertTrue(exception.getCause() instanceof CharacterCodingException);
            assertTrue(exception.transcript().text().length() <= 32);
            session.onExit().get(2, TimeUnit.SECONDS);
            ProtocolSessionException followUp = assertThrows(ProtocolSessionException.class, () -> session.request(""));
            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, followUp.reason());
        } finally {
            session.close();
        }
    }

    @Test
    void persistentResponseDecoderCannotRetainBytesAcrossRequestsWithoutBound() throws Exception {
        ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(), new TextLineAdapter(), call -> call.withArgs("controlled-line-repl")
                        .withTranscriptLimit(1024)
                        .withOutputBacklogLimit(64)
                        .withMaxResponseBytes(64)
                        .withCharsetPolicy(CharsetPolicy.report(new NewlineWithoutConsumptionCharset())));
        try {
            for (int request = 0; request < 5; request++) {
                assertEquals("ok", session.request("x", Duration.ofSeconds(2)));
            }

            ProtocolSessionException exception =
                    assertThrows(ProtocolSessionException.class, () -> session.request("x", Duration.ofSeconds(2)));

            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, exception.reason());
            assertTrue(exception.transcript().text().length() <= 1024);
            session.onExit().get(2, TimeUnit.SECONDS);
            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> session.request("x"));
            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, followUp.reason());
        } finally {
            session.close();
        }
    }

    @Test
    void parallelRequestsAreSerialized() throws Exception {
        try (ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(),
                new TwoLineTextAdapter(),
                call -> call.withArgs("two-line-delay-repl", "--delay-millis=100"))) {
            ExecutorService executor = Executors.newCachedThreadPool();
            try {
                Future<String> first = executor.submit(() -> session.request("a", Duration.ofSeconds(5)));
                Future<String> second = executor.submit(() -> session.request("b", Duration.ofSeconds(5)));

                List<String> responses = List.of(first.get(), second.get());

                assertTrue(responses.contains("start:a\nend:a"), () -> "interleaved response: " + responses);
                assertTrue(responses.contains("start:b\nend:b"), () -> "interleaved response: " + responses);
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void protocolTimeoutIncludesWaitingForSerializedAccess() throws Exception {
        CountDownLatch firstResponseStarted = new CountDownLatch(1);
        CoordinatedTwoLineAdapter adapter = new CoordinatedTwoLineAdapter(firstResponseStarted);

        try (ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(), adapter, call -> call.withArgs("two-line-delay-repl", "--delay-millis=300"))) {
            ExecutorService executor = Executors.newFixedThreadPool(2);
            try {
                Future<String> first = executor.submit(() -> session.request("first", Duration.ofSeconds(2)));
                assertTrue(firstResponseStarted.await(1, TimeUnit.SECONDS));

                Future<Throwable> queued =
                        executor.submit(() -> captureFailure(() -> session.request("queued", Duration.ofMillis(50))));
                ProtocolSessionException timeout =
                        assertInstanceOf(ProtocolSessionException.class, queued.get(1, TimeUnit.SECONDS));

                assertEquals(ProtocolSessionException.Reason.TIMEOUT, timeout.reason());
                assertEquals("start:first\nend:first", first.get());
                assertEquals(List.of("first"), adapter.writtenRequests());
                assertEquals("start:after\nend:after", session.request("after", Duration.ofSeconds(2)));
                assertEquals(List.of("first", "after"), adapter.writtenRequests());
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void protocolAdapterCannotReturnSuccessAfterRequestDeadline() throws Exception {
        CountDownLatch responseRead = new CountDownLatch(1);
        CountDownLatch releaseAdapter = new CountDownLatch(1);
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(),
                new SlowAfterReadAdapter(responseRead, releaseAdapter),
                call -> call.withArgs("controlled-line-repl"))) {
            Future<Throwable> request =
                    executor.submit(() -> captureFailure(() -> session.request("hello", Duration.ofMillis(500))));
            assertTrue(responseRead.await(2, TimeUnit.SECONDS));
            ProtocolSessionException timeout =
                    assertInstanceOf(ProtocolSessionException.class, request.get(2, TimeUnit.SECONDS));

            assertEquals(ProtocolSessionException.Reason.TIMEOUT, timeout.reason());
        } finally {
            releaseAdapter.countDown();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void nonCooperativeProtocolDecoderCannotBlockCallerPastRequestDeadline() throws Exception {
        CountDownLatch decoderStarted = new CountDownLatch(1);
        CountDownLatch releaseDecoder = new CountDownLatch(1);
        CountDownLatch decoderFinished = new CountDownLatch(1);
        AtomicReference<Thread> decoderThread = new AtomicReference<>();
        ProtocolAdapter<String, String> adapter = new TextLineAdapter() {
            @Override
            public String readResponse(ProtocolReaders readers) {
                decoderThread.set(Thread.currentThread());
                try {
                    String response = super.readResponse(readers);
                    decoderStarted.countDown();
                    awaitIgnoringInterrupts(releaseDecoder);
                    return response;
                } finally {
                    decoderFinished.countDown();
                }
            }
        };

        try (ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), adapter, call -> call.withArgs("controlled-line-repl"))) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Throwable> request =
                        executor.submit(() -> captureFailure(() -> session.request("hello", Duration.ofMillis(500))));
                ProtocolSessionException timeout =
                        assertInstanceOf(ProtocolSessionException.class, request.get(2, TimeUnit.SECONDS));

                assertEquals(ProtocolSessionException.Reason.TIMEOUT, timeout.reason());
                assertEquals(0, decoderStarted.getCount());
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        } finally {
            releaseDecoder.countDown();
            assertTrue(decoderFinished.await(1, TimeUnit.SECONDS));
            assertTaskStopped(decoderThread.get(), "protocol response decoder");
        }
    }

    @Test
    void nonCooperativeProtocolWriterCannotBlockCallerPastRequestDeadline() throws Exception {
        CountDownLatch writerStarted = new CountDownLatch(1);
        CountDownLatch releaseWriter = new CountDownLatch(1);
        CountDownLatch writerFinished = new CountDownLatch(1);
        AtomicReference<Thread> writerThread = new AtomicReference<>();
        ProtocolAdapter<String, String> adapter = new TextLineAdapter() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writerThread.set(Thread.currentThread());
                writerStarted.countDown();
                try {
                    awaitIgnoringInterrupts(releaseWriter);
                    super.writeRequest(request, writer);
                } finally {
                    writerFinished.countDown();
                }
            }
        };

        try (ProtocolSession<String, String> session =
                openProtocolSession(fixtureService(), adapter, call -> call.withArgs("controlled-line-repl"))) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<Throwable> request =
                        executor.submit(() -> captureFailure(() -> session.request("hello", Duration.ofMillis(500))));
                ProtocolSessionException timeout =
                        assertInstanceOf(ProtocolSessionException.class, request.get(2, TimeUnit.SECONDS));

                assertEquals(ProtocolSessionException.Reason.TIMEOUT, timeout.reason());
                assertEquals(0, writerStarted.getCount());
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        } finally {
            releaseWriter.countDown();
            assertTrue(writerFinished.await(1, TimeUnit.SECONDS));
            assertTaskStopped(writerThread.get(), "protocol request writer");
        }
    }

    @Test
    void reusableProtocolScenarioOpensOneConfiguredWorker() {
        AtomicBoolean adapterCreated = new AtomicBoolean();
        Supplier<ProtocolAdapter<String, String>> adapterFactory = () -> {
            assertTrue(adapterCreated.compareAndSet(false, true));
            return new FramedStringAdapter();
        };

        try (ProtocolSession<String, String> session = fixtureService()
                .protocolSession(adapterFactory)
                .withArgs("length-line-frame")
                .withReadiness(ready -> assertEquals("ready", ready.request("ready")))
                .open()) {
            assertEquals("hello", session.request("hello"));
            assertTrue(adapterCreated.get());
        }
    }

    @Test
    void pooledProtocolSessionReusesTypedWorkersWithoutExposingLease() {
        try (PooledProtocolSession<String, String> pool = fixtureService()
                .protocolSession(FramedStringAdapter::new)
                .withArgs("length-line-frame")
                .withReadiness(ready -> assertEquals("ready", ready.request("ready")))
                .pooled()
                .withMaxSize(2)
                .withWarmupSize(1)
                .withMinIdle(1)
                .open()) {
            assertEquals("first", pool.request("first"));
            assertEquals("second", pool.request("second"));

            PooledProtocolSessionMetrics metrics = pool.metrics();
            assertEquals(1, metrics.created());
            assertEquals(2, metrics.completedRequests());
            assertEquals(0, metrics.failedRequests());
            assertEquals(1, metrics.idle());
        }
    }

    @Test
    void pooledProtocolRejectsNullBeforeLeasingOrRetiringWorker() {
        try (PooledProtocolSession<String, String> pool = protocolPool(
                        fixtureService(), FramedStringAdapter::new, "length-line-frame")
                .withMaxSize(1)
                .withWarmupSize(1)
                .open()) {
            assertThrows(NullPointerException.class, () -> pool.request(null));
            assertThrows(NullPointerException.class, () -> pool.request(null, Duration.ofSeconds(1)));

            PooledProtocolSessionMetrics metrics = pool.metrics();
            assertEquals(1, metrics.idle());
            assertEquals(0, metrics.leased());
            assertEquals(0, metrics.retired());
            assertEquals(0, metrics.failedRequests());
            assertEquals("valid", pool.request("valid"));
        }
    }

    @Test
    void retiredProtocolWorkerCleansObservedDescendantBeforeMetricsPublication() throws Exception {
        AtomicLong observedChildPid = new AtomicLong();
        long childPid = -1;
        Supplier<ProtocolAdapter<String, String>> adapterFactory = () -> new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {}

            @Override
            public String readResponse(ProtocolReaders readers) {
                String child = readers.stdout().readLine(128);
                observedChildPid.set(Long.parseLong(child.substring("child:".length())));
                return readers.stdout().readLine(128);
            }
        };
        try (PooledProtocolSession<String, String> pool = fixtureService()
                .protocolSession(adapterFactory)
                .withArgs("spawn-child", "--child-scenario=never-exit", "--linger-millis=500")
                .pooled()
                .withMaxSize(1)
                .open()) {
            ProtocolSessionException failure =
                    assertThrows(ProtocolSessionException.class, () -> pool.request("request", Duration.ofSeconds(2)));
            childPid = observedChildPid.get();

            assertTrue(
                    failure.reason() == ProtocolSessionException.Reason.EOF
                            || failure.reason() == ProtocolSessionException.Reason.PROCESS_EXITED,
                    "unexpected protocol termination reason: " + failure.reason());
            assertTrue(childPid > 0, "worker output did not publish the child process id");
            assertTrue(PoolTestAccess.awaitProtocolMetrics(
                    pool, metrics -> metrics.retired() == 1, Duration.ofSeconds(2)));
            assertFalse(
                    ProcessHandle.of(childPid).map(ProcessHandle::isAlive).orElse(false),
                    "retired protocol worker left an observed descendant alive");
        } finally {
            if (childPid >= 0) {
                ProcessHandle.of(childPid).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    @Test
    void pooledProtocolCloseDistinguishesInterruptionFromDrainTimeout() throws Exception {
        CoordinatedResponseAdapter adapter = new CoordinatedResponseAdapter();
        PooledProtocolSession<String, String> pool = protocolPool(
                        fixtureService(), () -> adapter, "ignore-stdin", "--millis=5000")
                .withMaxSize(1)
                .open();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            Future<String> request = executor.submit(() -> pool.request("hold", Duration.ofSeconds(2)));
            assertTrue(adapter.awaitResponseEntered());
            assertEquals(1, pool.metrics().leased());
            CompletableFuture<Void> eventual = pool.closeAsync();

            PooledProtocolSessionException interrupted;
            try {
                Thread.currentThread().interrupt();
                interrupted = assertThrows(PooledProtocolSessionException.class, pool::close);
                assertEquals(true, Thread.currentThread().isInterrupted());
            } finally {
                Thread.interrupted();
            }

            assertEquals(PooledProtocolSessionException.Reason.INTERRUPTED, interrupted.reason());
            adapter.releaseResponse();
            assertEquals("slept:hold", request.get(2, TimeUnit.SECONDS));
            eventual.get(2, TimeUnit.SECONDS);
        } finally {
            adapter.releaseResponse();
            executor.shutdownNow();
            assertEquals(true, executor.awaitTermination(1, TimeUnit.SECONDS));
            pool.close();
        }
    }

    @Test
    void pooledProtocolSessionRecordsRetireReason() throws Exception {
        try (PooledProtocolSession<String, String> pool = protocolPool(
                        fixtureService(), FramedStringAdapter::new, "length-line-frame")
                .withMaxSize(1)
                .withMaxRequestsPerWorker(1)
                .open()) {
            assertEquals("first", pool.request("first"));

            pool.closeAsync().get(2, TimeUnit.SECONDS);
            PooledProtocolSessionMetrics metrics = pool.metrics();
            assertEquals(1L, metrics.retireReasons().get(PooledWorkerRetireReason.MAX_REQUESTS));
        }
    }

    @Test
    void pooledProtocolExitedWorkerUsesOnlyProcessExitedRetirementReason() {
        try (PooledProtocolSession<String, String> pool = protocolPool(
                        fixtureService(), TextLineAdapter::new, "exit-after-read", "--stdout=ok")
                .withMaxSize(1)
                .withWarmupSize(1)
                .withReset(worker -> worker.onExit().join())
                .open()) {
            assertEquals("ok", pool.request("first"));
            assertEquals("ok", pool.request("second"));

            assertEquals(1L, pool.metrics().retireReasons().get(PooledWorkerRetireReason.PROCESS_EXITED));
            assertFalse(pool.metrics().retireReasons().containsKey(PooledWorkerRetireReason.HEALTH_FAILED));
        }
    }

    @Test
    void poolDraftSettingsAreAppliedAtOpen() throws Exception {
        AtomicInteger responses = new AtomicInteger();
        CountDownLatch replacementCreated = new CountDownLatch(1);
        CountDownLatch secondResponseEntered = new CountDownLatch(1);
        CountDownLatch releaseSecondResponse = new CountDownLatch(1);
        AtomicInteger adapters = new AtomicInteger();
        Supplier<ProtocolAdapter<String, String>> adapterFactory = () -> {
            if (adapters.incrementAndGet() == 2) {
                replacementCreated.countDown();
            }
            return new FramedStringAdapter() {
                @Override
                public String readResponse(ProtocolReaders readers) {
                    String response = super.readResponse(readers);
                    if (responses.incrementAndGet() == 2) {
                        secondResponseEntered.countDown();
                        awaitIgnoringInterrupts(releaseSecondResponse);
                    }
                    return response;
                }
            };
        };
        try (PooledProtocolSession<String, String> pool = protocolPool(
                        fixtureService(), adapterFactory, "length-line-frame")
                .withMaxSize(1)
                .withWarmupSize(1)
                .withMinIdle(1)
                .withMaxRequestsPerWorker(1)
                .open()) {
            assertEquals("first", pool.request("first"));
            assertTrue(replacementCreated.await(2, TimeUnit.SECONDS));
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<String> second = executor.submit(() -> pool.request("second"));
                assertTrue(secondResponseEntered.await(2, TimeUnit.SECONDS));

                PooledProtocolSessionMetrics metrics = pool.metrics();
                assertEquals(1L, metrics.retireReasons().get(PooledWorkerRetireReason.MAX_REQUESTS));
                assertEquals(1, metrics.size());
                assertEquals(1, metrics.leased());
                assertEquals(2, metrics.created());

                releaseSecondResponse.countDown();
                assertEquals("second", second.get(2, TimeUnit.SECONDS));
            } finally {
                releaseSecondResponse.countDown();
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void pooledProtocolWarmupReadinessFailureIsStartupFailure() {
        PooledProtocolSessionException exception =
                assertThrows(PooledProtocolSessionException.class, () -> fixtureService()
                        .protocolSession(FramedStringAdapter::new)
                        .withArgs("length-line-frame")
                        .withReadiness(ready -> {
                            throw new IllegalStateException("not ready");
                        })
                        .pooled()
                        .withWarmupSize(1)
                        .open());

        assertEquals(PooledProtocolSessionException.Reason.STARTUP_FAILED, exception.reason());
    }

    @Test
    void pooledProtocolAcquireTimeoutIsDistinctFromRequestTimeout() throws Exception {
        CoordinatedResponseAdapter adapter = new CoordinatedResponseAdapter();
        try (PooledProtocolSession<String, String> pool = protocolPool(
                        fixtureService(), () -> adapter, "ignore-stdin", "--millis=5000")
                .withMaxSize(1)
                .withAcquireTimeout(Duration.ofMillis(100))
                .open()) {
            ExecutorService executor = Executors.newCachedThreadPool();
            CountDownLatch firstStarted = new CountDownLatch(1);
            try {
                Future<String> first = executor.submit(() -> {
                    firstStarted.countDown();
                    return pool.request("first", Duration.ofSeconds(2));
                });
                assertEquals(true, firstStarted.await(1, TimeUnit.SECONDS));
                assertTrue(adapter.awaitResponseEntered());
                assertEquals(1, pool.metrics().leased());

                PooledProtocolSessionException exception =
                        assertThrows(PooledProtocolSessionException.class, () -> pool.request("second"));

                assertEquals(PooledProtocolSessionException.Reason.ACQUIRE_TIMEOUT, exception.reason());
                adapter.releaseResponse();
                assertEquals("slept:first", first.get(2, TimeUnit.SECONDS));
            } finally {
                adapter.releaseResponse();
                executor.shutdownNow();
                assertEquals(true, executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void pooledProtocolCreatesOneAdapterPerWorker() throws Exception {
        ConcurrentLinkedQueue<Integer> adapterIds = new ConcurrentLinkedQueue<>();
        AtomicIntegerAdapterFactory factory = new AtomicIntegerAdapterFactory(adapterIds);

        try (PooledProtocolSession<String, String> pool = protocolPool(
                        fixtureService(), factory::newAdapter, "length-line-frame")
                .withMaxSize(2)
                .withWarmupSize(2)
                .open()) {
            ExecutorService executor = Executors.newCachedThreadPool();
            try {
                Future<String> first = executor.submit(() -> pool.request("first"));
                Future<String> second = executor.submit(() -> pool.request("second"));

                assertEquals(true, first.get(2, TimeUnit.SECONDS).matches("adapter-[12]:first"));
                assertEquals(true, second.get(2, TimeUnit.SECONDS).matches("adapter-[12]:second"));
                assertEquals(2, adapterIds.size());
                assertTrue(adapterIds.containsAll(List.of(1, 2)));
            } finally {
                executor.shutdownNow();
                assertEquals(true, executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void protocolPoolHealthHookTimeoutIsBounded() throws Exception {
        NonCooperativeTask health = new NonCooperativeTask();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            try (PooledProtocolSession<String, String> pool = protocolPool(
                            fixtureService(), FramedStringAdapter::new, "length-line-frame")
                    .withMaxSize(1)
                    .withWarmupSize(1)
                    .withHookTimeout(Duration.ofMillis(50))
                    .withHealthCheck(worker -> {
                        health.run();
                        return true;
                    })
                    .open()) {
                Future<PooledProtocolSessionException> request = executor.submit(() -> {
                    try {
                        pool.request("hello");
                        throw new AssertionError("expected health timeout");
                    } catch (PooledProtocolSessionException exception) {
                        return exception;
                    }
                });
                PooledProtocolSessionException exception = request.get(1, TimeUnit.SECONDS);

                assertTrue(health.awaitEntered());
                assertEquals(PooledProtocolSessionException.Reason.HOOK_TIMEOUT, exception.reason());
                health.releaseAndJoin();
                assertEquals("second", pool.request("second"));
                PooledProtocolSessionMetrics metrics = pool.metrics();
                assertEquals(1, metrics.retired());
                assertEquals(1, metrics.retireReasons().get(PooledWorkerRetireReason.HEALTH_FAILED));
                assertFalse(metrics.retireReasons().containsKey(PooledWorkerRetireReason.PROCESS_EXITED));
                assertEquals(2, metrics.created());
            }
        } finally {
            health.release();
            health.join();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void protocolPoolResetHookTimeoutRetiresWorkerWithoutChangingCompletedRequestOutcome() throws Exception {
        NonCooperativeTask reset = new NonCooperativeTask();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            try (PooledProtocolSession<String, String> pool = protocolPool(
                            fixtureService(), FramedStringAdapter::new, "length-line-frame")
                    .withMaxSize(1)
                    .withHookTimeout(Duration.ofMillis(50))
                    .withReset(worker -> reset.run())
                    .open()) {
                Future<String> request = executor.submit(() -> pool.request("hello"));
                String response = request.get(1, TimeUnit.SECONDS);

                assertTrue(reset.awaitEntered());
                assertEquals("hello", response);
                assertEquals(1, pool.metrics().completedRequests());
                assertEquals(0, pool.metrics().failedRequests());
                reset.releaseAndJoin();
                assertEquals("second", pool.request("second"));
                PooledProtocolSessionMetrics metrics = pool.metrics();
                assertEquals(1, metrics.retired());
                assertEquals(1, metrics.retireReasons().get(PooledWorkerRetireReason.RESET_FAILED));
                assertEquals(2, metrics.created());
            }
        } finally {
            reset.release();
            reset.join();
            executor.shutdownNow();
            assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void protocolPoolResetErrorIsRethrownAfterRecordingCompletedRequestAndResetRetirement() throws Exception {
        AssertionError resetError = new AssertionError("reset invariant failed");
        try (PooledProtocolSession<String, String> pool = protocolPool(
                        fixtureService(), FramedStringAdapter::new, "length-line-frame")
                .withMaxSize(1)
                .withReset(worker -> {
                    throw resetError;
                })
                .open()) {
            AssertionError thrown = assertThrows(AssertionError.class, () -> pool.request("hello"));

            assertSame(resetError, thrown);
            pool.closeAsync().get(2, TimeUnit.SECONDS);
            PooledProtocolSessionMetrics metrics = pool.metrics();
            assertEquals(1, metrics.completedRequests());
            assertEquals(0, metrics.failedRequests());
            assertEquals(1, metrics.retired());
            assertEquals(1, metrics.retireReasons().get(PooledWorkerRetireReason.RESET_FAILED));
        }
    }

    @Test
    void protocolPoolRequestErrorIsRethrownAndRecordedAsFailedRequest() throws Exception {
        AssertionError decoderError = new AssertionError("decoder invariant failed");
        Supplier<ProtocolAdapter<String, String>> adapterFactory = () -> new FramedStringAdapter() {
            @Override
            public String readResponse(ProtocolReaders readers) {
                super.readResponse(readers);
                throw decoderError;
            }
        };
        try (PooledProtocolSession<String, String> pool = protocolPool(
                        fixtureService(), adapterFactory, "length-line-frame")
                .withMaxSize(1)
                .open()) {
            AssertionError thrown = assertThrows(AssertionError.class, () -> pool.request("hello"));

            assertSame(decoderError, thrown);
            pool.closeAsync().get(2, TimeUnit.SECONDS);
            PooledProtocolSessionMetrics metrics = pool.metrics();
            assertEquals(0, metrics.completedRequests());
            assertEquals(1, metrics.failedRequests());
            assertEquals(1, metrics.retired());
            assertEquals(1, metrics.retireReasons().get(PooledWorkerRetireReason.WORKER_FAILED));
        }
    }

    @Test
    void protocolPoolRetiresWorkerWhenDecoderReturnsNull() throws Exception {
        Supplier<ProtocolAdapter<String, String>> adapterFactory = () -> new FramedStringAdapter() {
            @Override
            public String readResponse(ProtocolReaders readers) {
                super.readResponse(readers);
                return null;
            }
        };
        try (PooledProtocolSession<String, String> pool = fixtureService()
                .protocolSession(adapterFactory)
                .withArgs("length-line-frame")
                .withTranscriptLimit(8)
                .pooled()
                .withMaxSize(1)
                .withWarmupSize(1)
                .withBackgroundReplenishment(false)
                .open()) {
            ProtocolSessionException exception = assertThrows(
                    ProtocolSessionException.class, () -> pool.request("0123456789abcdef0123456789abcdef"));

            assertEquals(ProtocolSessionException.Reason.PROTOCOL_DECODER_FAILED, exception.reason());
            assertInstanceOf(NullPointerException.class, exception.getCause());
            assertTrue(exception.transcript().truncated());
            assertTrue(exception.transcript().text().length() <= 8);
            pool.closeAsync().get(2, TimeUnit.SECONDS);
            PooledProtocolSessionMetrics metrics = pool.metrics();
            assertEquals(0, metrics.completedRequests());
            assertEquals(1, metrics.failedRequests());
            assertEquals(1, metrics.retired());
            assertEquals(1, metrics.retireReasons().get(PooledWorkerRetireReason.DECODER_FAILED));
        }
    }

    private static CommandService fixtureService() {
        return Procwright.command(TestCliSupport.command());
    }

    private static <I, O> ProtocolSession<I, O> openProtocolSession(
            CommandService service,
            ProtocolAdapter<I, O> adapter,
            UnaryOperator<ProtocolSessionScenario.Draft<I, O>> configure) {
        return configure.apply(service.protocolSession(() -> adapter)).open();
    }

    private static <I, O> ProtocolSessionScenario.PoolDraft<I, O> protocolPool(
            CommandService service,
            Supplier<? extends ProtocolAdapter<I, O>> adapterFactory,
            String... workerArguments) {
        return service.protocolSession(adapterFactory).withArgs(workerArguments).pooled();
    }

    private static class FramedStringAdapter implements ProtocolAdapter<String, String> {

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            byte[] body = request.getBytes(StandardCharsets.UTF_8);
            writer.writeLine(Integer.toString(body.length));
            writer.write(body);
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            ProtocolReader stdout = readers.stdout();
            int length = parseLength(stdout.readLine(32));
            byte[] body = stdout.readExactly(length);
            assertEquals("", stdout.readLine(1));
            assertEquals("END", stdout.readLine(8));
            return new String(body, StandardCharsets.UTF_8);
        }
    }

    private static class TextLineAdapter implements ProtocolAdapter<String, String> {

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.write(request);
            writer.write("\n");
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            return readers.stdout().readLine(64);
        }
    }

    private static final class FramedBytesAsLineAdapter implements ProtocolAdapter<byte[], String> {

        @Override
        public void writeRequest(byte[] request, ProtocolWriter writer) {
            writer.writeLine(Integer.toString(request.length));
            writer.write(Arrays.copyOf(request, request.length));
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            ProtocolReader stdout = readers.stdout();
            parseLength(stdout.readLine(32));
            String body = stdout.readTextUntil((byte) '\n', 32);
            assertEquals("END", stdout.readLine(8));
            return body;
        }
    }

    private static final class ExactTextFieldAdapter implements ProtocolAdapter<byte[], String> {

        @Override
        public void writeRequest(byte[] request, ProtocolWriter writer) {
            writer.writeLine(Integer.toString(request.length));
            writer.write(request);
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            ProtocolReader stdout = readers.stdout();
            int length = parseLength(stdout.readLine(32));
            return stdout.readTextExactly(length, 32);
        }
    }

    private static final class StdoutLineAdapter implements ProtocolAdapter<String, String> {

        private final int maxChars;

        private StdoutLineAdapter(int maxChars) {
            this.maxChars = maxChars;
        }

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            return readers.stdout().readLine(maxChars);
        }
    }

    private static final class StderrLineAdapter implements ProtocolAdapter<String, String> {

        private final int maxChars;

        private StderrLineAdapter(int maxChars) {
            this.maxChars = maxChars;
        }

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            return readers.stderr().readLine(maxChars);
        }
    }

    private static final class StderrEchoAdapter implements ProtocolAdapter<String, String> {

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.writeLine(":stderr " + request);
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            return readers.stderr().readLine(64);
        }
    }

    private static final class TwoLineTextAdapter implements ProtocolAdapter<String, String> {

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.writeLine(request);
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            ProtocolReader stdout = readers.stdout();
            return stdout.readLine(32) + "\n" + stdout.readLine(32);
        }
    }

    private static final class CoordinatedTwoLineAdapter implements ProtocolAdapter<String, String> {

        private final CountDownLatch firstResponseStarted;
        private final List<String> writtenRequests = new ArrayList<>();

        private CoordinatedTwoLineAdapter(CountDownLatch firstResponseStarted) {
            this.firstResponseStarted = firstResponseStarted;
        }

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writtenRequests.add(request);
            writer.writeLine(request);
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            ProtocolReader stdout = readers.stdout();
            String first = stdout.readLine(32);
            firstResponseStarted.countDown();
            return first + "\n" + stdout.readLine(32);
        }

        private List<String> writtenRequests() {
            return List.copyOf(writtenRequests);
        }
    }

    private static final class SlowAfterReadAdapter implements ProtocolAdapter<String, String> {

        private final CountDownLatch responseRead;
        private final CountDownLatch release;

        private SlowAfterReadAdapter(CountDownLatch responseRead, CountDownLatch release) {
            this.responseRead = responseRead;
            this.release = release;
        }

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.writeLine(request);
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            String response = readers.stdout().readLine(64);
            responseRead.countDown();
            awaitIgnoringInterrupts(release);
            return response;
        }
    }

    private static final class FailingDecoderAdapter implements ProtocolAdapter<String, String> {

        private final IllegalArgumentException failure;

        private FailingDecoderAdapter(IllegalArgumentException failure) {
            this.failure = failure;
        }

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            throw failure;
        }
    }

    private static final class DelimiterBytesAdapter implements ProtocolAdapter<String, byte[]> {

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            writer.flush();
        }

        @Override
        public byte[] readResponse(ProtocolReaders readers) {
            return readers.stdout().readUntil((byte) '\n', 512 * 1024);
        }
    }

    private static final class CoordinatedResponseAdapter implements ProtocolAdapter<String, String> {

        private final CountDownLatch responseEntered = new CountDownLatch(1);
        private final CountDownLatch releaseResponse = new CountDownLatch(1);
        private String request;

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            this.request = request;
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            responseEntered.countDown();
            awaitIgnoringInterrupts(releaseResponse);
            return "slept:" + request;
        }

        private boolean awaitResponseEntered() throws InterruptedException {
            return responseEntered.await(1, TimeUnit.SECONDS);
        }

        private void releaseResponse() {
            releaseResponse.countDown();
        }
    }

    private static final class NoProgressCharset extends Charset {

        private NoProgressCharset() {
            super("X-Procwright-Protocol-No-Progress", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    return CoderResult.UNDERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class FiniteErrorAfterExhaustionCharset extends Charset {

        private FiniteErrorAfterExhaustionCharset() {
            super("X-Procwright-Protocol-Finite-Error-After-Exhaustion", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                private boolean consumed;
                private int exhaustedErrors;

                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (!consumed) {
                        input.position(input.limit());
                        consumed = true;
                        return CoderResult.OVERFLOW;
                    }
                    if (exhaustedErrors++ < 4) {
                        return CoderResult.malformedForLength(1);
                    }
                    return CoderResult.UNDERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class PersistentResponseInvalidReplacementCharset extends Charset {

        private final AtomicInteger createdDecoders = new AtomicInteger();

        private PersistentResponseInvalidReplacementCharset() {
            super("X-Procwright-Persistent-Response-Invalid-Replacement", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            if (createdDecoders.getAndIncrement() == 2) {
                return invalidReplacementDecoder();
            }
            return passthroughDecoder();
        }

        private CharsetDecoder invalidReplacementDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                private boolean consumed;

                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    if (!consumed) {
                        input.position(input.limit());
                        consumed = true;
                        return CoderResult.OVERFLOW;
                    }
                    return CoderResult.malformedForLength(1);
                }
            };
        }

        private CharsetDecoder passthroughDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    while (input.hasRemaining() && output.hasRemaining()) {
                        output.put((char) Byte.toUnsignedInt(input.get()));
                    }
                    return input.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class PersistentTranscriptRuntimeFailureCharset extends Charset {

        private final AtomicInteger createdDecoders = new AtomicInteger();
        private final IllegalArgumentException failure = new IllegalArgumentException("transcript decoder failed");
        private final CountDownLatch beforeFailure;

        private PersistentTranscriptRuntimeFailureCharset(CountDownLatch beforeFailure) {
            super("X-Procwright-Persistent-Transcript-Runtime-Failure", new String[0]);
            this.beforeFailure = beforeFailure;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            if (createdDecoders.getAndIncrement() < 2) {
                return new CharsetDecoder(this, 1, 1) {
                    @Override
                    protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                        beforeFailure.countDown();
                        throw failure;
                    }
                };
            }
            return passthroughDecoder();
        }

        private CharsetDecoder passthroughDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    while (input.hasRemaining() && output.hasRemaining()) {
                        output.put((char) Byte.toUnsignedInt(input.get()));
                    }
                    return input.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
                }
            };
        }

        private IllegalArgumentException failure() {
            return failure;
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class PersistentTranscriptNewDecoderFailureCharset extends Charset {

        private final int failingCreation;
        private final AtomicInteger createdDecoders = new AtomicInteger();
        private final IllegalArgumentException failure = new IllegalArgumentException("new decoder failed");

        private PersistentTranscriptNewDecoderFailureCharset(int failingCreation) {
            super("X-Procwright-Persistent-Transcript-New-Decoder-Failure", new String[0]);
            this.failingCreation = failingCreation;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            if (createdDecoders.incrementAndGet() == failingCreation) {
                throw failure;
            }
            return passthroughDecoder();
        }

        private CharsetDecoder passthroughDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    while (input.hasRemaining() && output.hasRemaining()) {
                        output.put((char) Byte.toUnsignedInt(input.get()));
                    }
                    return input.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
                }
            };
        }

        private IllegalArgumentException failure() {
            return failure;
        }

        private int decoderCreations() {
            return createdDecoders.get();
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class PersistentTranscriptFlushFailureCharset extends Charset {

        private final AtomicInteger createdDecoders = new AtomicInteger();
        private final IllegalArgumentException failure = new IllegalArgumentException("decoder flush failed");

        private PersistentTranscriptFlushFailureCharset() {
            super("X-Procwright-Persistent-Transcript-Flush-Failure", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            if (createdDecoders.getAndIncrement() < 2) {
                return new CharsetDecoder(this, 1, 1) {
                    @Override
                    protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                        while (input.hasRemaining() && output.hasRemaining()) {
                            output.put((char) Byte.toUnsignedInt(input.get()));
                        }
                        return input.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
                    }

                    @Override
                    protected CoderResult implFlush(CharBuffer output) {
                        throw failure;
                    }
                };
            }
            return passthroughDecoder();
        }

        private CharsetDecoder passthroughDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    while (input.hasRemaining() && output.hasRemaining()) {
                        output.put((char) Byte.toUnsignedInt(input.get()));
                    }
                    return input.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
                }
            };
        }

        private IllegalArgumentException failure() {
            return failure;
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class AlternatingEncoderCharset extends Charset {

        private final AtomicInteger createdEncoders = new AtomicInteger();

        private AlternatingEncoderCharset() {
            super("X-Procwright-Alternating-Encoder", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    while (input.hasRemaining() && output.hasRemaining()) {
                        output.put((char) Byte.toUnsignedInt(input.get()));
                    }
                    return input.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            boolean duplicate = createdEncoders.getAndIncrement() > 0;
            return new CharsetEncoder(this, 1, duplicate ? 2 : 1) {
                @Override
                protected CoderResult encodeLoop(CharBuffer input, ByteBuffer output) {
                    int bytesPerChar = duplicate ? 2 : 1;
                    while (input.hasRemaining()) {
                        if (output.remaining() < bytesPerChar) {
                            return CoderResult.OVERFLOW;
                        }
                        byte value = (byte) input.get();
                        output.put(value);
                        if (duplicate) {
                            output.put(value);
                        }
                    }
                    return CoderResult.UNDERFLOW;
                }
            };
        }

        private int encoderCreations() {
            return createdEncoders.get();
        }
    }

    private static final class NewlineWithoutConsumptionCharset extends Charset {

        private NewlineWithoutConsumptionCharset() {
            super("X-Procwright-Newline-Without-Consumption", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 3) {
                private int emittedLines;

                @Override
                protected CoderResult decodeLoop(ByteBuffer input, CharBuffer output) {
                    int availableLines = 0;
                    for (int index = input.position(); index < input.limit(); index++) {
                        if (input.get(index) == (byte) '\n') {
                            availableLines++;
                        }
                    }
                    if (availableLines <= emittedLines) {
                        return CoderResult.UNDERFLOW;
                    }
                    if (output.remaining() < 3) {
                        return CoderResult.OVERFLOW;
                    }
                    output.put("ok\n");
                    emittedLines++;
                    return CoderResult.UNDERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class AtomicIntegerAdapterFactory {

        private final ConcurrentLinkedQueue<Integer> adapterIds;
        private final AtomicInteger nextId = new AtomicInteger();

        private AtomicIntegerAdapterFactory(ConcurrentLinkedQueue<Integer> adapterIds) {
            this.adapterIds = adapterIds;
        }

        private ProtocolAdapter<String, String> newAdapter() {
            int id = nextId.incrementAndGet();
            adapterIds.add(id);
            return new WorkerScopedAdapter(id);
        }
    }

    private static final class WorkerScopedAdapter implements ProtocolAdapter<String, String> {

        private final int id;
        private String request;

        private WorkerScopedAdapter(int id) {
            this.id = id;
        }

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            this.request = request;
            byte[] body = request.getBytes(StandardCharsets.UTF_8);
            writer.writeLine(Integer.toString(body.length));
            writer.write(body);
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            ProtocolReader stdout = readers.stdout();
            int length = parseLength(stdout.readLine(32));
            stdout.readExactly(length);
            assertEquals("", stdout.readLine(1));
            assertEquals("END", stdout.readLine(8));
            return "adapter-" + id + ":" + request;
        }
    }

    private static final class NonCooperativeTask {

        private final CountDownLatch entered = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private volatile Thread thread;

        private void run() {
            thread = Thread.currentThread();
            entered.countDown();
            awaitIgnoringInterrupts(release);
        }

        private boolean awaitEntered() {
            try {
                return entered.await(1, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return false;
            }
        }

        private void release() {
            release.countDown();
        }

        private void releaseAndJoin() throws InterruptedException {
            release();
            join();
        }

        private void join() throws InterruptedException {
            Thread callback = thread;
            if (callback != null) {
                callback.join(TimeUnit.SECONDS.toMillis(1));
                assertFalse(callback.isAlive(), "lifecycle callback did not finish");
            }
        }
    }

    private static void assertTaskStopped(Thread thread, String task) throws InterruptedException {
        assertTrue(thread != null, task + " thread was not captured");
        thread.join(TimeUnit.SECONDS.toMillis(1));
        assertFalse(thread.isAlive(), task + " thread retained its bounded-runner permit");
    }

    private static boolean causeChainContains(Throwable failure, Throwable expected) {
        Throwable current = failure;
        while (current != null) {
            if (current == expected) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    private static Throwable captureFailure(ThrowingOperation operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    @FunctionalInterface
    private interface ThrowingOperation {

        void run() throws Throwable;
    }

    private static void assertIdentitySuppressedOnce(Throwable primary, Throwable expected) {
        long occurrences = Arrays.stream(primary.getSuppressed())
                .filter(candidate -> candidate == expected)
                .count();
        assertEquals(1, occurrences);
        assertFalse(causeChainContains(expected, primary));
        assertFalse(Arrays.stream(expected.getSuppressed()).anyMatch(candidate -> candidate == primary));
    }

    private static void awaitIgnoringInterrupts(CountDownLatch latch) {
        boolean interrupted = false;
        while (true) {
            try {
                latch.await();
                break;
            } catch (InterruptedException exception) {
                interrupted = true;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static int parseLength(String line) {
        if (!line.startsWith("len:")) {
            throw new IllegalArgumentException("missing length prefix");
        }
        return Integer.parseInt(line.substring("len:".length()));
    }
}
