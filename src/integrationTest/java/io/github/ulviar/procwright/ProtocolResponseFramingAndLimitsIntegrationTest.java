/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.FramedStringAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.StdoutLineAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.TwoLineTextAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.fixtureService;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.openProtocolSession;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.parseLength;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class ProtocolResponseFramingAndLimitsIntegrationTest {

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
            assertExitFailedWith(session, exception);
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
            assertExitFailedWith(session, exception);
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
            assertExitFailedWith(session, exception);
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
            assertExitFailedWith(session, failure);
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
            assertExitFailedWith(session, exception);
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

    private static void assertExitFailedWith(ProtocolSession<?, ?> session, ProtocolSessionException requestFailure) {
        ExecutionException observed =
                assertThrows(ExecutionException.class, () -> session.onExit().get(2, TimeUnit.SECONDS));
        ProtocolSessionException exitFailure = assertInstanceOf(ProtocolSessionException.class, observed.getCause());
        assertEquals(requestFailure.reason(), exitFailure.reason());
        assertSame(requestFailure, exitFailure);
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
}
