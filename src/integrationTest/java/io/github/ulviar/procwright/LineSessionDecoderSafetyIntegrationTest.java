/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.LineSessionIntegrationFixtures.fixtureScenario;
import static io.github.ulviar.procwright.LineSessionIntegrationFixtures.openLineSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.LineSessionException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CharsetEncoder;
import java.nio.charset.CoderResult;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class LineSessionDecoderSafetyIntegrationTest {

    @Test
    void stdoutDecoderWithoutProgressFailsAndClosesLineSession() throws Exception {
        assertNoProgressDecoderClosesLineSession("o".repeat(4096), "");
    }

    @Test
    void stderrDecoderWithoutProgressFailsAndClosesLineSession() throws Exception {
        assertNoProgressDecoderClosesLineSession("", "e".repeat(4096));
    }

    @Test
    void outputOnlyDecoderCannotGrowLineBeforeOuterLimitsApply() throws Exception {
        LineSessionScenario.Draft service = fixtureScenario()
                .withTranscriptLimit(1024)
                .withMaxLineChars(256)
                .withCharsetPolicy(CharsetPolicy.report(new OutputOnlyOverflowCharset()));
        LineSession session = openLineSession(
                service, call -> call.withArgs("partial", "--stdout=x", "--stderr=", "--hold-millis=5000"));
        try {
            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("", Duration.ofSeconds(2)));

            assertEquals(LineSessionException.Reason.DECODE_ERROR, exception.reason());
            assertTrue(exception.transcript().text().length() <= 1024);
            session.onExit().get(2, TimeUnit.SECONDS);
            LineSessionException followUp = assertThrows(LineSessionException.class, () -> session.request(""));
            assertEquals(LineSessionException.Reason.DECODE_ERROR, followUp.reason());
        } finally {
            session.close();
        }
    }

    @Test
    void rewindingDecoderFailsBeforeRepeatedOutputAndClosesLineSession() throws Exception {
        LineSessionScenario.Draft service = fixtureScenario()
                .withTranscriptLimit(1024)
                .withMaxLineChars(1024)
                .withCharsetPolicy(CharsetPolicy.report(new FiniteRewindingCharset()));
        LineSession session = openLineSession(
                service, call -> call.withArgs("partial", "--stdout=x", "--stderr=", "--hold-millis=5000"));
        try {
            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("", Duration.ofSeconds(2)));

            assertEquals(LineSessionException.Reason.DECODE_ERROR, exception.reason());
            assertTrue(exception.transcript().text().length() <= 256);
            session.onExit().get(2, TimeUnit.SECONDS);
            LineSessionException followUp = assertThrows(LineSessionException.class, () -> session.request(""));
            assertEquals(LineSessionException.Reason.DECODE_ERROR, followUp.reason());
        } finally {
            session.close();
        }
    }

    private static void assertNoProgressDecoderClosesLineSession(String stdout, String stderr) throws Exception {
        LineSessionScenario.Draft service = fixtureScenario()
                .withTranscriptLimit(32)
                .withMaxLineChars(32)
                .withCharsetPolicy(CharsetPolicy.report(new NoProgressCharset()));
        LineSession session = openLineSession(
                service,
                call -> call.withArgs("partial", "--stdout=" + stdout, "--stderr=" + stderr, "--hold-millis=5000"));
        try {
            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("", Duration.ofSeconds(2)));

            assertEquals(LineSessionException.Reason.DECODE_ERROR, exception.reason());
            assertTrue(exception.transcript().text().length() <= 32);
            session.onExit().get(2, TimeUnit.SECONDS);
            LineSessionException followUp = assertThrows(LineSessionException.class, () -> session.request(""));
            assertEquals(LineSessionException.Reason.DECODE_ERROR, followUp.reason());
        } finally {
            session.close();
        }
    }

    private static final class NoProgressCharset extends Charset {

        private NoProgressCharset() {
            super("X-Procwright-Line-No-Progress", new String[0]);
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
                    return CoderResult.UNDERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class OutputOnlyOverflowCharset extends Charset {

        private OutputOnlyOverflowCharset() {
            super("X-Procwright-Line-Output-Only-Overflow", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 128) {
                @Override
                protected CoderResult decodeLoop(java.nio.ByteBuffer input, java.nio.CharBuffer output) {
                    while (output.hasRemaining()) {
                        output.put('x');
                    }
                    return CoderResult.OVERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class FiniteRewindingCharset extends Charset {

        private FiniteRewindingCharset() {
            super("X-Procwright-Line-Finite-Rewinding", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 128) {
                private int calls;

                @Override
                protected CoderResult decodeLoop(java.nio.ByteBuffer input, java.nio.CharBuffer output) {
                    calls++;
                    if (calls > 4) {
                        return CoderResult.malformedForLength(1);
                    }
                    input.position((calls & 1) == 1 ? 1 : 0);
                    while (output.hasRemaining()) {
                        output.put('r');
                    }
                    return CoderResult.OVERFLOW;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }
}
