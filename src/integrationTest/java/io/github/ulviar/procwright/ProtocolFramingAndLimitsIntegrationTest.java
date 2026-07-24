/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.ProtocolFramingIntegrationSupport.AlternatingEncoderCharset;
import static io.github.ulviar.procwright.ProtocolFramingIntegrationSupport.DelimiterBytesAdapter;
import static io.github.ulviar.procwright.ProtocolFramingIntegrationSupport.ExactTextFieldAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.FramedStringAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.StdoutLineAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.TextLineAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.TwoLineTextAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.fixtureService;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.openProtocolSession;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.parseLength;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReader;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

final class ProtocolFramingAndLimitsIntegrationTest {

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
}
