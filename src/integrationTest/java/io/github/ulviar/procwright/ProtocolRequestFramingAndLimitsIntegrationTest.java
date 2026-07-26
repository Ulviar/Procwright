/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.FramedStringAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.TextLineAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.fixtureService;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.openProtocolSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.ProtocolSessionException;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

final class ProtocolRequestFramingAndLimitsIntegrationTest {

    @Test
    void protocolSessionSupportsMultilineStringRequests() {
        try (ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(), new FramedStringAdapter(), call -> call.withArgs("length-line-frame"))) {
            String response = session.request("one\ntwo", Duration.ofSeconds(2));

            assertEquals("one\ntwo", response);
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
            assertExitFailedWith(session, exception);
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
            assertExitFailedWith(session, exception);
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

    private static final class AlternatingEncoderCharset extends Charset {

        private final AtomicInteger createdEncoders = new AtomicInteger();

        AlternatingEncoderCharset() {
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

        int encoderCreations() {
            return createdEncoders.get();
        }
    }
}
