/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.ResponseDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

final class LineSessionDecoderAndSizeLimitsIntegrationTest extends LineSessionDecoderIntegrationSupport {

    @Test
    void customDecoderCanReadMultipleLines() {
        LineSessionScenario.Draft service =
                fixtureScenario().withResponseDecoder(reader -> List.of(reader.readLine(), reader.readLine()));

        try (LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl"))) {
            LineResponse response = session.request("multi");

            assertEquals(List.of("first:multi", "second:multi"), response.lines());
            assertEquals("first:multi\nsecond:multi", response.text());
        }
    }

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
    void eofBeforeResponseIsDistinct() {
        try (LineSession session = openLineSession(fixtureScenario(), call -> call.withArgs("exit-after-read"))) {
            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofSeconds(1)));

            assertEquals(LineSessionException.Reason.EOF, exception.reason());
        }
    }

    @Test
    void decoderFailureIsDistinct() {
        LineSessionScenario.Draft service = fixtureScenario().withResponseDecoder(reader -> {
            throw new IllegalArgumentException("bad response");
        });

        try (LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl"))) {
            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofSeconds(1)));

            assertEquals(LineSessionException.Reason.DECODER_FAILED, exception.reason());
        }
    }

    @Test
    void decoderErrorClosesSessionAfterConsumingResponse() throws Exception {
        AssertionError decoderError = new AssertionError("decoder failed");
        LineSessionScenario.Draft service = fixtureScenario().withResponseDecoder(reader -> {
            reader.readLine();
            throw decoderError;
        });

        try (LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl"))) {
            AssertionError thrown =
                    assertThrows(AssertionError.class, () -> session.request("hello", Duration.ofSeconds(1)));

            assertSame(decoderError, thrown);
            session.onExit().get(2, TimeUnit.SECONDS);
            LineSessionException followUp =
                    assertThrows(LineSessionException.class, () -> session.request("again", Duration.ofSeconds(1)));
            assertEquals(LineSessionException.Reason.DECODER_FAILED, followUp.reason());
        }
    }

    @Test
    void responseLimitsApplyToConsumedProtocolInputNotTransformedDecoderOutput() {
        ResponseDecoder expandingDecoder = reader -> {
            assertEquals("x", reader.readLine());
            return List.of("expanded".repeat(100));
        };
        LineSessionScenario.Draft service = fixtureScenario()
                .withMaxResponseLines(1)
                .withMaxResponseChars(1)
                .withResponseDecoder(expandingDecoder);

        try (LineSession session = openLineSession(
                service, call -> call.withArgs("partial", "--stdout=x\n", "--stderr=", "--hold-millis=5000"))) {
            LineResponse response = session.request("ignored", Duration.ofSeconds(2));

            assertEquals(List.of("expanded".repeat(100)), response.lines());
        }
    }

    @Test
    void lineOfExactlyMaxLineCharsWithLineFeedTerminatorSucceeds() {
        LineSessionScenario.Draft service = fixtureScenario().withMaxLineChars("response:hello".length());

        try (LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl"))) {
            assertEquals("response:hello", session.request("hello").text());
        }
    }

    @Test
    void lineOfExactlyMaxLineCharsWithCrLfTerminatorSucceeds() {
        LineSessionScenario.Draft service = fixtureScenario().withMaxLineChars("response:hello".length());

        try (LineSession session =
                openLineSession(service, call -> call.withArgs("controlled-line-repl", "--crlf=true"))) {
            assertEquals("response:hello", session.request("hello").text());
        }
    }

    @Test
    void lineBeyondMaxLineCharsIsTypedFailureForBothTerminators() {
        for (String[] args : new String[][] {{"controlled-line-repl"}, {"controlled-line-repl", "--crlf=true"}}) {
            LineSessionScenario.Draft service = fixtureScenario().withMaxLineChars("response:hello".length() - 1);

            try (LineSession session = openLineSession(service, call -> call.withArgs(args))) {
                LineSessionException exception =
                        assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofSeconds(2)));

                assertEquals(LineSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
            }
        }
    }

    @Test
    void responseLineLimitAppliesAcrossCustomDecoderReads() {
        ResponseDecoder decoder = reader -> {
            java.util.ArrayList<String> lines = new java.util.ArrayList<>();
            while (true) {
                String line = reader.readLine();
                lines.add(line);
                if (line.equals("done")) {
                    return lines;
                }
            }
        };
        LineSessionScenario.Draft service =
                fixtureScenario().withMaxResponseLines(2).withResponseDecoder(decoder);

        try (LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl"))) {
            LineSessionException exception = assertThrows(LineSessionException.class, () -> session.request("many"));

            assertEquals(LineSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
        }
    }

    @Test
    void responseCharacterLimitAppliesAcrossCustomDecoderReads() {
        ResponseDecoder decoder = reader -> List.of(reader.readLine(), reader.readLine());
        LineSessionScenario.Draft service =
                fixtureScenario().withMaxResponseChars(20).withResponseDecoder(decoder);

        try (LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl"))) {
            LineSessionException exception = assertThrows(LineSessionException.class, () -> session.request("multi"));

            assertEquals(LineSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
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

    @Test
    void strictCharsetPolicyReportsDecodeErrorAsTypedFailure() {
        LineSessionScenario.Draft service =
                fixtureScenario().withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8));

        try (LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl"))) {
            LineSessionException exception = assertThrows(
                    LineSessionException.class, () -> session.request("malformed-utf8", Duration.ofSeconds(2)));

            assertEquals(LineSessionException.Reason.DECODE_ERROR, exception.reason());

            LineSessionException followUp =
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofSeconds(1)));
            assertEquals(LineSessionException.Reason.DECODE_ERROR, followUp.reason());
            assertTrue(followUp.getMessage().contains("closed by an earlier failure"));
        }
    }

    @Test
    void strictCharsetPolicyReportsMalformedStderrAsTypedFailure() {
        LineSessionScenario.Draft service =
                fixtureScenario().withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8));

        try (LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl"))) {
            LineSessionException exception = assertThrows(
                    LineSessionException.class, () -> session.request("malformed-stderr-utf8", Duration.ofSeconds(2)));

            assertEquals(LineSessionException.Reason.DECODE_ERROR, exception.reason());
            assertTrue(exception.transcript().malformed());
        }
    }

    @Test
    void replacingCharsetPolicyMarksMalformedStderrWithoutFailingRequest() throws Exception {
        try (LineSession session = openLineSession(fixtureScenario(), call -> call.withArgs("controlled-line-repl"))) {
            LineResponse response = session.request("malformed-stderr-utf8", Duration.ofSeconds(2));

            assertEquals("response:malformed-stderr-utf8", response.text());
            assertTrue(awaitMalformedTranscript(session));
            assertTrue(session.transcript().text().contains("\uFFFD"));
        }
    }

    @Test
    void multiByteCodepointSplitAcrossChunksDecodesCorrectly() {
        LineSessionScenario.Draft service =
                fixtureScenario().withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8));

        try (LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl"))) {
            assertEquals(
                    "П", session.request("split-utf8", Duration.ofSeconds(2)).text());
        }
    }

    @Test
    void crlfTerminatedResponsesDecodeWithoutTerminators() {
        try (LineSession session =
                openLineSession(fixtureScenario(), call -> call.withArgs("controlled-line-repl", "--crlf=true"))) {
            for (String request : List.of("alpha", "beta")) {
                LineResponse response = session.request(request, Duration.ofSeconds(2));

                assertEquals(List.of("response:" + request), response.lines());
                assertFalse(response.text().contains("\r"), "decoded lines must not retain CRLF terminators");
            }
        }
    }

    @Test
    void mixedLineTerminatorsWithinOneSessionDecodeWithoutManualNormalization() {
        // With --crlf=true the fixture terminates regular responses with CRLF while ":multi" output
        // stays LF-terminated, so one session observes both styles.
        ResponseDecoder decoder = reader -> {
            String first = reader.readLine();
            if (first.startsWith("multi:")) {
                return List.of(first, reader.readLine());
            }
            return List.of(first);
        };
        LineSessionScenario.Draft service = fixtureScenario().withResponseDecoder(decoder);

        try (LineSession session = openLineSession(service, call -> call.withArgs("line-repl", "--crlf=true"))) {
            assertEquals(
                    List.of("multi:0", "multi:1"), session.request(":multi 2").lines());
            assertEquals(List.of("response:hello"), session.request("hello").lines());
        }
    }

    @Test
    void carriageReturnWithoutLineFeedIsContentNotTerminator() {
        try (LineSession session = openLineSession(
                fixtureScenario(),
                call -> call.withArgs(
                        "binary", "--pattern=hex", "--hex=616c7068610d626574610a", "--hold-millis=5000"))) {
            assertEquals(
                    "alpha\rbeta",
                    session.request("ignored", Duration.ofSeconds(2)).text());
        }
    }

    @Test
    void unterminatedStdoutLineIsBounded() {
        LineSessionScenario.Draft service = fixtureScenario().withMaxLineChars(32);

        try (LineSession session = openLineSession(
                service,
                call -> call.withArgs("partial", "--stdout=" + "x".repeat(128), "--stderr=", "--hold-millis=5000"))) {
            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofSeconds(2)));

            assertEquals(LineSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
            assertTrue(exception.getCause().getMessage().contains("maxLineChars"));
        }
    }

    @Test
    void loneCarriageReturnAtEofCannotExceedLineLimit() {
        LineSessionScenario.Draft service = fixtureScenario().withMaxLineChars(3);

        try (LineSession session = openLineSession(
                service,
                call -> call.withArgs(
                        "binary", "--pattern=hex", "--hex=7878780d", "--stream=stdout", "--hold-millis=100"))) {
            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofSeconds(2)));

            assertEquals(LineSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
            assertTrue(exception.getCause().getMessage().contains("maxLineChars"));
        }
    }
}
