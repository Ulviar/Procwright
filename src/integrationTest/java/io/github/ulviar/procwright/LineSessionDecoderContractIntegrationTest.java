/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class LineSessionDecoderContractIntegrationTest extends LineSessionIntegrationSupport {

    @Test
    void stdoutDecoderWithoutProgressFailsAndClosesLineSession() throws Exception {
        assertNoProgressDecoderClosesLineSession("o".repeat(4096), "");
    }

    @Test
    void stderrDecoderWithoutProgressFailsAndClosesLineSession() throws Exception {
        assertNoProgressDecoderClosesLineSession("", "e".repeat(4096));
    }

    @Test
    void decoderInitializationRuntimeFailureClosesLineSessionBeforeEitherPumpStarts() {
        for (int failingCreation : List.of(1, 2)) {
            IllegalArgumentException cause =
                    new IllegalArgumentException("decoder creation " + failingCreation + " failed");
            IndexedNewDecoderFailureCharset charset = new IndexedNewDecoderFailureCharset(failingCreation, cause);
            LineSessionScenario.Draft service =
                    fixtureScenario().withTranscriptLimit(32).withCharsetPolicy(CharsetPolicy.report(charset));

            LineSessionException exception = assertThrows(
                    LineSessionException.class,
                    () -> openLineSession(
                            service, call -> call.withArgs("partial", "--stdout=", "--stderr=", "--hold-millis=5000")));

            assertEquals(LineSessionException.Reason.DECODE_ERROR, exception.reason());
            assertSame(cause, exception.getCause());
            assertTrue(exception.transcript().text().length() <= 32);
            assertEquals(failingCreation, charset.decoderCreations());
        }
    }

    @Test
    void decoderRuntimeFailureClosesLineSessionForEitherOutputStream() throws Exception {
        for (boolean failStdout : List.of(true, false)) {
            IllegalArgumentException cause = new IllegalArgumentException("line decoder failed");
            LineSessionScenario.Draft service = fixtureScenario()
                    .withTranscriptLimit(32)
                    .withCharsetPolicy(CharsetPolicy.report(new MarkerRuntimeFailureCharset((byte) 'x', cause)));
            String stdout = failStdout ? "x" : "";
            String stderr = failStdout ? "" : "x";
            LineSession session = openLineSession(
                    service,
                    call -> call.withArgs("partial", "--stdout=" + stdout, "--stderr=" + stderr, "--hold-millis=5000"));
            try {
                LineSessionException exception =
                        assertThrows(LineSessionException.class, () -> session.request("", Duration.ofSeconds(2)));

                assertEquals(LineSessionException.Reason.DECODE_ERROR, exception.reason());
                assertTrue(causeChainContains(exception, cause));
                assertTrue(exception.transcript().text().length() <= 32);
                session.onExit().get(2, TimeUnit.SECONDS);
            } finally {
                session.close();
            }
        }
    }

    @Test
    void decoderFlushRuntimeFailureClosesLineSession() throws Exception {
        IllegalArgumentException cause = new IllegalArgumentException("line decoder flush failed");
        LineSessionScenario.Draft service = fixtureScenario()
                .withTranscriptLimit(32)
                .withCharsetPolicy(CharsetPolicy.report(new RuntimeFailureOnFlushCharset(cause)));
        LineSession session = openLineSession(
                service, call -> call.withArgs("partial", "--stdout=x", "--stderr=", "--hold-millis=100"));
        try {
            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("", Duration.ofSeconds(2)));

            assertEquals(LineSessionException.Reason.DECODE_ERROR, exception.reason());
            assertTrue(causeChainContains(exception, cause));
            assertTrue(exception.transcript().text().length() <= 32);
            session.onExit().get(2, TimeUnit.SECONDS);
        } finally {
            session.close();
        }
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

    @Test
    void customDecoderCannotSwallowResponseLimitFailure() throws Exception {
        ResponseDecoder decoder = reader -> {
            try {
                reader.readLine();
                reader.readLine();
                return List.of("unexpected");
            } catch (LineSessionException ignored) {
                return List.of("fallback");
            }
        };
        LineSessionScenario.Draft service =
                fixtureScenario().withMaxResponseLines(1).withResponseDecoder(decoder);
        LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl"));
        try {
            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("multi", Duration.ofSeconds(2)));

            assertEquals(LineSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
            session.onExit().get(2, TimeUnit.SECONDS);
            LineSessionException followUp = assertThrows(LineSessionException.class, () -> session.request("again"));
            assertEquals(LineSessionException.Reason.RESPONSE_TOO_LARGE, followUp.reason());
        } finally {
            session.close();
        }
    }

    @Test
    void caughtLineReaderFailurePrecedesSecondaryRuntimeException() throws Exception {
        IllegalArgumentException secondaryFailure = new IllegalArgumentException("secondary line decoder failure");
        ResponseDecoder decoder = reader -> {
            try {
                reader.readLine();
                reader.readLine();
                return List.of("unexpected");
            } catch (LineSessionException ignored) {
                throw secondaryFailure;
            }
        };
        LineSessionScenario.Draft service =
                fixtureScenario().withMaxResponseLines(1).withResponseDecoder(decoder);
        LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl"));
        try {
            LineSessionException failure =
                    assertThrows(LineSessionException.class, () -> session.request("multi", Duration.ofSeconds(2)));

            assertEquals(LineSessionException.Reason.RESPONSE_TOO_LARGE, failure.reason());
            session.onExit().get(2, TimeUnit.SECONDS);
            LineSessionException followUp = assertThrows(LineSessionException.class, () -> session.request("again"));
            assertEquals(LineSessionException.Reason.RESPONSE_TOO_LARGE, followUp.reason());
        } finally {
            session.close();
        }
    }

    @Test
    void caughtLineReaderFailurePrecedesSecondaryErrorButRethrowsThatError() throws Exception {
        AssertionError secondaryFailure = new AssertionError("secondary line decoder error");
        ResponseDecoder decoder = reader -> {
            try {
                reader.readLine();
                reader.readLine();
                return List.of("unexpected");
            } catch (LineSessionException ignored) {
                throw secondaryFailure;
            }
        };
        LineSessionScenario.Draft service =
                fixtureScenario().withMaxResponseLines(1).withResponseDecoder(decoder);
        LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl"));
        try {
            AssertionError thrown =
                    assertThrows(AssertionError.class, () -> session.request("multi", Duration.ofSeconds(2)));

            assertSame(secondaryFailure, thrown);
            session.onExit().get(2, TimeUnit.SECONDS);
            LineSessionException followUp = assertThrows(LineSessionException.class, () -> session.request("again"));
            assertEquals(LineSessionException.Reason.RESPONSE_TOO_LARGE, followUp.reason());
        } finally {
            session.close();
        }
    }

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
            session.onExit().get(2, TimeUnit.SECONDS);
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
            session.onExit().get(2, TimeUnit.SECONDS);
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

    private static CharsetDecoder passthroughDecoder(Charset charset) {
        return new CharsetDecoder(charset, 1, 1) {
            @Override
            protected CoderResult decodeLoop(java.nio.ByteBuffer input, java.nio.CharBuffer output) {
                while (input.hasRemaining() && output.hasRemaining()) {
                    output.put((char) Byte.toUnsignedInt(input.get()));
                }
                return input.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
            }
        };
    }

    private static final class IndexedNewDecoderFailureCharset extends Charset {

        private final int failingCreation;
        private final RuntimeException failure;
        private int decoderCreations;

        IndexedNewDecoderFailureCharset(int failingCreation, RuntimeException failure) {
            super("X-Procwright-Line-Indexed-New-Decoder-Failure-" + failingCreation, new String[0]);
            this.failingCreation = failingCreation;
            this.failure = failure;
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            decoderCreations++;
            if (decoderCreations == failingCreation) {
                throw failure;
            }
            return passthroughDecoder(this);
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }

        int decoderCreations() {
            return decoderCreations;
        }
    }

    private static final class MarkerRuntimeFailureCharset extends Charset {

        private final byte marker;
        private final RuntimeException failure;

        MarkerRuntimeFailureCharset(byte marker, RuntimeException failure) {
            super("X-Procwright-Line-Marker-Runtime-Failure", new String[0]);
            this.marker = marker;
            this.failure = failure;
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
                        if (input.get(input.position()) == marker) {
                            throw failure;
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

    private static final class RuntimeFailureOnFlushCharset extends Charset {

        private final RuntimeException failure;

        RuntimeFailureOnFlushCharset(RuntimeException failure) {
            super("X-Procwright-Line-Runtime-Failure-On-Flush", new String[0]);
            this.failure = failure;
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
                        output.put((char) Byte.toUnsignedInt(input.get()));
                    }
                    return input.hasRemaining() ? CoderResult.OVERFLOW : CoderResult.UNDERFLOW;
                }

                @Override
                protected CoderResult implFlush(java.nio.CharBuffer output) {
                    throw failure;
                }
            };
        }

        @Override
        public CharsetEncoder newEncoder() {
            return StandardCharsets.UTF_8.newEncoder();
        }
    }

    private static final class OutputThenMalformedCharset extends Charset {

        OutputThenMalformedCharset() {
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

        MalformedBangCharset(CountDownLatch beforeFailure) {
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

    private static final class NoProgressCharset extends Charset {

        NoProgressCharset() {
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

        OutputOnlyOverflowCharset() {
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

        FiniteRewindingCharset() {
            super("X-Procwright-Line-Finite-Rewinding", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return false;
        }

        @Override
        public CharsetDecoder newDecoder() {
            return new CharsetDecoder(this, 1, 128) {
                int calls;

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
