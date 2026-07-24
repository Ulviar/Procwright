/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.ProtocolFailureIntegrationSupport.FailingDecoderAdapter;
import static io.github.ulviar.procwright.ProtocolFailureIntegrationSupport.FiniteErrorAfterExhaustionCharset;
import static io.github.ulviar.procwright.ProtocolFailureIntegrationSupport.FramedBytesAsLineAdapter;
import static io.github.ulviar.procwright.ProtocolFailureIntegrationSupport.NewlineWithoutConsumptionCharset;
import static io.github.ulviar.procwright.ProtocolFailureIntegrationSupport.NoProgressCharset;
import static io.github.ulviar.procwright.ProtocolFailureIntegrationSupport.PersistentResponseInvalidReplacementCharset;
import static io.github.ulviar.procwright.ProtocolFailureIntegrationSupport.PersistentTranscriptFlushFailureCharset;
import static io.github.ulviar.procwright.ProtocolFailureIntegrationSupport.PersistentTranscriptNewDecoderFailureCharset;
import static io.github.ulviar.procwright.ProtocolFailureIntegrationSupport.PersistentTranscriptRuntimeFailureCharset;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.FramedStringAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.StdoutLineAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.TextLineAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.awaitIgnoringInterrupts;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.fixtureService;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.openProtocolSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class ProtocolTerminalFailureIntegrationTest {

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
    void strictCharsetPolicyReportsMalformedOutput() {
        ProtocolSessionException exception = assertThrows(ProtocolSessionException.class, () -> openProtocolSession(
                        fixtureService(), new FramedBytesAsLineAdapter(), call -> call.withArgs("length-line-frame")
                                .withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8)))
                .request(new byte[] {(byte) 0xFF}));

        assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, exception.reason());
        assertEquals(true, exception.transcript().malformed());
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

    private static void assertIdentitySuppressedOnce(Throwable primary, Throwable expected) {
        long occurrences = Arrays.stream(primary.getSuppressed())
                .filter(candidate -> candidate == expected)
                .count();
        assertEquals(1, occurrences);
        assertFalse(causeChainContains(expected, primary));
        assertFalse(Arrays.stream(expected.getSuppressed()).anyMatch(candidate -> candidate == primary));
    }
}
