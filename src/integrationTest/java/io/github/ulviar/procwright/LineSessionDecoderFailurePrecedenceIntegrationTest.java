/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.LineSessionIntegrationFixtures.awaitIgnoringInterrupts;
import static io.github.ulviar.procwright.LineSessionIntegrationFixtures.fixtureScenario;
import static io.github.ulviar.procwright.LineSessionIntegrationFixtures.openLineSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.ResponseDecoder;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class LineSessionDecoderFailurePrecedenceIntegrationTest {

    @Test
    void customDecoderCannotSwallowDecodeFailure() throws Exception {
        ResponseDecoder decoder = reader -> {
            try {
                return List.of(reader.readLine());
            } catch (LineSessionException ignored) {
                return List.of("fallback");
            }
        };
        LineSessionScenario.Draft service = fixtureScenario()
                .withCharsetPolicy(CharsetPolicy.report(new OutputThenMalformedCharset()))
                .withResponseDecoder(decoder);
        LineSession session = openLineSession(
                service, call -> call.withArgs("partial", "--stdout=x", "--stderr=", "--hold-millis=5000"));
        try {
            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("", Duration.ofSeconds(2)));

            assertEquals(LineSessionException.Reason.DECODE_ERROR, exception.reason());
            assertFalse(exception.transcript().text().contains("ok"));
            assertTrue(exception.transcript().text().length() <= 64 * 1024);
            assertExitFailedWith(session, exception);
            LineSessionException followUp = assertThrows(LineSessionException.class, () -> session.request(""));
            assertEquals(LineSessionException.Reason.DECODE_ERROR, followUp.reason());
        } finally {
            session.close();
        }
    }

    @Test
    void concurrentStderrDecodeFailureWinsBeforeLineResponseSuccess() throws Exception {
        CountDownLatch beforeFailure = new CountDownLatch(1);
        AtomicReference<LineSession> sessionReference = new AtomicReference<>();
        ResponseDecoder decoder = reader -> {
            String line = reader.readLine();
            awaitIgnoringInterrupts(beforeFailure);
            sessionReference.get().onExit().join();
            return List.of(line);
        };
        LineSessionScenario.Draft service = fixtureScenario()
                .withCharsetPolicy(CharsetPolicy.report(new MalformedBangCharset(beforeFailure)))
                .withResponseDecoder(decoder);
        LineSession session = openLineSession(
                service, call -> call.withArgs("partial", "--stdout=ok\n", "--stderr=!", "--hold-millis=5000"));
        sessionReference.set(session);
        try {
            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("", Duration.ofSeconds(2)));

            assertEquals(LineSessionException.Reason.DECODE_ERROR, exception.reason());
            assertExitFailedWith(session, exception);
        } finally {
            session.close();
        }
    }

    private static void assertExitFailedWith(LineSession session, LineSessionException requestFailure) {
        ExecutionException observed =
                assertThrows(ExecutionException.class, () -> session.onExit().get(2, TimeUnit.SECONDS));
        assertTrue(causeChainContains(requestFailure, observed.getCause()));
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

    private static final class OutputThenMalformedCharset extends Charset {

        private OutputThenMalformedCharset() {
            super("X-Procwright-Line-Output-Then-Malformed", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 3) {
                @Override
                protected CoderResult decodeLoop(java.nio.ByteBuffer input, java.nio.CharBuffer output) {
                    if (!input.hasRemaining()) {
                        return CoderResult.UNDERFLOW;
                    }
                    output.put("ok\n");
                    return CoderResult.malformedForLength(1);
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class MalformedBangCharset extends Charset {

        private final CountDownLatch beforeFailure;

        private MalformedBangCharset(CountDownLatch beforeFailure) {
            super("X-Procwright-Line-Malformed-Bang", new String[0]);
            this.beforeFailure = beforeFailure;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 1) {
                @Override
                protected CoderResult decodeLoop(java.nio.ByteBuffer input, java.nio.CharBuffer output) {
                    while (input.hasRemaining() && output.hasRemaining()) {
                        if (input.get(input.position()) == (byte) '!') {
                            beforeFailure.countDown();
                            return CoderResult.malformedForLength(1);
                        }
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
}
