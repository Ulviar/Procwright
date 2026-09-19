/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.StdoutLineAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.TextLineAdapter;
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
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ProtocolResponseDecodingFailureIntegrationTest {

    @Test
    void strictCharsetPolicyReportsMalformedOutput() {
        ProtocolSessionException exception = assertThrows(
                ProtocolSessionException.class,
                () -> openProtocolSession(
                                fixtureService(),
                                new FramedBytesAsLineAdapter(),
                                call -> call.withArgs("length-line-frame")
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
        ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(),
                adapter,
                call -> call.withArgs("partial", "--stdout=x", "--stderr=", "--hold-millis=5000")
                        .withTranscriptLimit(32)
                        .withCharsetPolicy(CharsetPolicy.replace(new PersistentResponseInvalidReplacementCharset())));
        try {
            ProtocolSessionException exception =
                    assertThrows(ProtocolSessionException.class, () -> session.request(""));

            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, exception.reason());
            assertTrue(exception.transcript().text().length() <= 32);
            assertExitFailedWith(session, exception);
            ProtocolSessionException followUp = assertThrows(ProtocolSessionException.class, () -> session.request(""));
            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, followUp.reason());
        } finally {
            session.close();
        }
    }

    @Test
    void persistentTextDecoderContractViolationIsDecodeErrorAndClosesSession() throws Exception {
        ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(),
                new StdoutLineAdapter(16),
                call -> call.withArgs("partial", "--stdout=x", "--stderr=", "--hold-millis=5000")
                        .withTranscriptLimit(32)
                        .withCharsetPolicy(CharsetPolicy.replace(new PersistentResponseInvalidReplacementCharset())));
        try {
            ProtocolSessionException exception =
                    assertThrows(ProtocolSessionException.class, () -> session.request("", Duration.ofSeconds(2)));

            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, exception.reason());
            assertTrue(exception.getCause() instanceof CharacterCodingException);
            assertTrue(exception.transcript().text().length() <= 32);
            assertExitFailedWith(session, exception);
            ProtocolSessionException followUp = assertThrows(ProtocolSessionException.class, () -> session.request(""));
            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, followUp.reason());
        } finally {
            session.close();
        }
    }

    @Test
    void persistentResponseDecoderCannotRetainBytesAcrossRequestsWithoutBound() throws Exception {
        ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(),
                new TextLineAdapter(),
                call -> call.withArgs("controlled-line-repl")
                        .withTranscriptLimit(1024)
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
            assertExitFailedWith(session, exception);
            ProtocolSessionException followUp =
                    assertThrows(ProtocolSessionException.class, () -> session.request("x"));
            assertEquals(ProtocolSessionException.Reason.DECODE_ERROR, followUp.reason());
        } finally {
            session.close();
        }
    }

    private static void assertExitFailedWith(ProtocolSession<?, ?> session, ProtocolSessionException requestFailure) {
        ExecutionException observed =
                assertThrows(ExecutionException.class, () -> session.onExit().get(2, TimeUnit.SECONDS));
        ProtocolSessionException exitFailure = assertInstanceOf(ProtocolSessionException.class, observed.getCause());
        assertEquals(requestFailure.reason(), exitFailure.reason());
        assertSame(requestFailure, exitFailure);
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
}
