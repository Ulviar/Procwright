/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.session.LineResponse;
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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;

final class LineSessionIntegrationTest {

    @Test
    void requestSendsLineAndReadsDefaultResponse() {
        try (LineSession session = openLineSession(fixtureScenario(), call -> call.withArgs("controlled-line-repl"))) {
            LineResponse response = session.request("hello");

            assertEquals(List.of("response:hello"), response.lines());
            assertEquals("response:hello", response.text());
            assertTrue(response.transcript().text().contains("stdout: response:hello"));
        }
    }

    @Test
    void readinessProbeRunsBeforeLineSessionIsReturned() {
        try (LineSession session = openLineSession(fixtureScenario(), call -> call.withArgs("controlled-line-repl")
                .withReadiness(ready ->
                        assertEquals("response:healthy", ready.request("health").text()))
                .withReadinessTimeout(Duration.ofSeconds(2)))) {
            LineResponse response = session.request("hello");

            assertEquals("response:hello", response.text());
        }
    }

    @Test
    void readinessFailureClosesLineSessionBeforeReturn() {
        CommandExecutionException exception = assertThrows(
                CommandExecutionException.class,
                () -> openLineSession(fixtureScenario(), call -> call.withArgs("controlled-line-repl")
                        .withReadiness(ready -> {
                            throw new IllegalStateException("not ready");
                        })
                        .withReadinessTimeout(Duration.ofSeconds(2))));

        assertEquals(CommandExecutionException.Reason.READINESS_FAILED, exception.reason());
    }

    @Test
    void requestRejectsEmbeddedLineSeparators() {
        try (LineSession session = openLineSession(fixtureScenario(), call -> call.withArgs("controlled-line-repl"))) {
            assertThrows(IllegalArgumentException.class, () -> session.request("a\nb"));
            assertThrows(IllegalArgumentException.class, () -> session.request("a\rb"));
        }
    }

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
    void timeoutAfterRequestWriteClosesSessionAndPreservesTypedFailure() throws Exception {
        ResponseDecoder waitForDone = reader -> {
            while (true) {
                String line = reader.readLine();
                if (line.equals("done")) {
                    return List.of(line);
                }
            }
        };
        LineSessionScenario.Draft service = fixtureScenario().withResponseDecoder(waitForDone);

        try (LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl"))) {
            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("slow", Duration.ofSeconds(1)));

            assertEquals(LineSessionException.Reason.TIMEOUT, exception.reason());
            assertTrue(exception.transcript().text().contains("stdout: started:slow"));
            session.onExit().get(2, TimeUnit.SECONDS);
            assertTrue(session.onExit().isDone());
            LineSessionException followUp =
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofSeconds(1)));
            assertEquals(LineSessionException.Reason.TIMEOUT, followUp.reason());
            assertTrue(followUp.getMessage().contains("closed by an earlier failure"));
        }
    }

    @Test
    void timeoutTranscriptIncludesPartialUnterminatedOutput() {
        try (LineSession session = openLineSession(
                fixtureScenario(),
                call -> call.withArgs("partial", "--stdout=", "--stderr=partial-error", "--hold-millis=5000"))) {
            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofSeconds(1)));

            assertEquals(LineSessionException.Reason.TIMEOUT, exception.reason());
            assertTrue(exception.transcript().text().contains("stderr: partial-error"));
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
    void transcriptAttributesInterleavedPartialOutputToStreams() {
        try (LineSession session = openLineSession(
                fixtureScenario(),
                call -> call.withArgs(
                        "partial", "--stdout=partial-out", "--stderr=partial-err", "--hold-millis=5000"))) {
            LineSessionException exception = assertThrows(
                    LineSessionException.class, () -> session.request("hello", timeoutAfterFixtureStartup()));

            assertEquals(LineSessionException.Reason.TIMEOUT, exception.reason());
            assertTrue(exception.transcript().text().contains("stdout: partial-out"));
            assertTrue(exception.transcript().text().contains("stderr: partial-err"));
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
    void stdoutBacklogOverflowIsDistinctFailure() {
        ResponseDecoder delayedDecoder = reader -> {
            sleep(Duration.ofMillis(300));
            return List.of(reader.readLine());
        };
        LineSessionScenario.Draft service =
                fixtureScenario().withStdoutBacklogLines(1).withResponseDecoder(delayedDecoder);

        try (LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl"))) {
            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("many", Duration.ofSeconds(2)));

            assertEquals(LineSessionException.Reason.STDOUT_BACKLOG_OVERFLOW, exception.reason());
        }
    }

    @Test
    void stdoutBacklogCharacterBudgetBoundsMultiplePendingLines() {
        ResponseDecoder delayedDecoder = reader -> {
            sleep(Duration.ofMillis(300));
            return List.of(reader.readLine());
        };
        LineSessionScenario.Draft service = fixtureScenario()
                .withStdoutBacklogLines(100)
                .withStdoutBacklogChars(40)
                .withResponseDecoder(delayedDecoder);

        try (LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl"))) {
            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("many", Duration.ofSeconds(2)));

            assertEquals(LineSessionException.Reason.STDOUT_BACKLOG_OVERFLOW, exception.reason());
        }
    }

    @Test
    void terminalEventDoesNotConsumeLineBacklogCapacity() {
        ResponseDecoder delayedDecoder = reader -> {
            sleep(Duration.ofMillis(300));
            return List.of(reader.readLine());
        };
        LineSessionScenario.Draft service =
                fixtureScenario().withStdoutBacklogLines(1).withResponseDecoder(delayedDecoder);

        try (LineSession session =
                openLineSession(service, call -> call.withArgs("exit-after-read", "--stdout=only-line"))) {
            LineResponse response = session.request("request", Duration.ofSeconds(2));

            assertEquals(List.of("only-line"), response.lines());
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
    void requestCharacterLimitFailsBeforeProtocolStateChanges() {
        LineSessionScenario.Draft service = fixtureScenario().withMaxRequestChars(4);

        try (LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl"))) {
            LineSessionException exception = assertThrows(LineSessionException.class, () -> session.request("hello"));

            assertEquals(LineSessionException.Reason.REQUEST_TOO_LARGE, exception.reason());
            assertFalse(session.onExit().isDone());
            assertEquals("response:okay", session.request("okay").text());
        }
    }

    @Test
    void requestValidationFailureLeavesSessionOpenForRetry() {
        try (LineSession session = openLineSession(fixtureScenario(), call -> call.withArgs("controlled-line-repl"))) {
            IllegalArgumentException exception =
                    assertThrows(IllegalArgumentException.class, () -> session.request("invalid\nline"));

            assertEquals("line must not contain line separators", exception.getMessage());
            assertFalse(session.onExit().isDone());
            assertEquals("response:valid", session.request("valid").text());
        }
    }

    @Test
    void encodedRequestByteLimitIsIndependentFromCharacterLimit() {
        LineSessionScenario.Draft service = fixtureScenario()
                .withCharset(StandardCharsets.UTF_16LE)
                .withMaxRequestChars(8)
                .withMaxRequestBytes(4);

        try (LineSession session =
                openLineSession(service, call -> call.withArgs("controlled-line-repl", "--charset=UTF-16LE"))) {
            LineSessionException exception = assertThrows(LineSessionException.class, () -> session.request("ab"));

            assertEquals(LineSessionException.Reason.REQUEST_TOO_LARGE, exception.reason());
            assertFalse(session.onExit().isDone());
            assertEquals("response:a", session.request("a").text());
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
    void callerInterruptDuringRequestIsTypedFailureAndRestoresInterruptStatus() throws Exception {
        try (LineSession session = openLineSession(
                fixtureScenario(), call -> call.withArgs("controlled-line-repl", "--slow-response-millis=60000"))) {
            CountDownLatch requestStarted = new CountDownLatch(1);
            AtomicReference<Throwable> thrown = new AtomicReference<>();
            AtomicBoolean interruptedAfterCatch = new AtomicBoolean();
            Thread caller = new Thread(() -> {
                try {
                    requestStarted.countDown();
                    session.request("slow-response", Duration.ofSeconds(30));
                } catch (Throwable throwable) {
                    thrown.set(throwable);
                    interruptedAfterCatch.set(Thread.currentThread().isInterrupted());
                }
            });

            caller.start();
            assertTrue(requestStarted.await(5, TimeUnit.SECONDS));
            assertTrue(
                    eventuallyTranscriptContains(session, "request-started:slow-response"),
                    "worker must receive the request before caller interruption");
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(10));

            assertFalse(caller.isAlive(), "interrupted caller must not stay blocked in request()");
            assertTrue(
                    thrown.get() instanceof LineSessionException,
                    () -> "expected typed line-session failure, got " + thrown.get());
            assertEquals(LineSessionException.Reason.FAILURE, ((LineSessionException) thrown.get()).reason());
            assertTrue(interruptedAfterCatch.get(), "caller interrupt status must be restored after the typed failure");
            session.onExit().get(2, TimeUnit.SECONDS);
        }
    }

    @Test
    void requestAfterStdoutBacklogOverflowReportsOverflowReason() {
        ResponseDecoder delayedDecoder = reader -> {
            sleep(Duration.ofMillis(300));
            return List.of(reader.readLine());
        };
        LineSessionScenario.Draft service =
                fixtureScenario().withStdoutBacklogLines(1).withResponseDecoder(delayedDecoder);

        try (LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl"))) {
            LineSessionException overflow =
                    assertThrows(LineSessionException.class, () -> session.request("many", Duration.ofSeconds(2)));
            assertEquals(LineSessionException.Reason.STDOUT_BACKLOG_OVERFLOW, overflow.reason());

            LineSessionException followUp =
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofSeconds(1)));

            assertEquals(LineSessionException.Reason.STDOUT_BACKLOG_OVERFLOW, followUp.reason());
            assertTrue(followUp.getMessage().contains("closed by an earlier failure"));
        }
    }

    @Test
    void requestAgainstExitedProcessReportsProcessExited() throws Exception {
        try (LineSession session =
                openLineSession(fixtureScenario(), call -> call.withArgs("exit", "--stdout=gone\n"))) {
            session.onExit().get(2, TimeUnit.SECONDS);

            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofSeconds(1)));

            assertEquals(LineSessionException.Reason.PROCESS_EXITED, exception.reason());
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

    @Test
    void transcriptIsBounded() {
        LineSessionScenario.Draft service = fixtureScenario()
                .withTranscriptLimit(80)
                .withResponseDecoder(reader -> {
                    String line;
                    do {
                        line = reader.readLine();
                    } while (!line.equals("done"));
                    return List.of(line);
                });

        try (LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl"))) {
            LineResponse response = session.request("many");

            assertEquals("done", response.text());
            assertTrue(response.transcript().truncated());
            assertTrue(response.transcript().text().contains("done"));
        }
    }

    @Test
    void stderrIsDrainedWhileWaitingForStdoutResponse() throws Exception {
        try (LineSession session = openLineSession(fixtureScenario(), call -> call.withArgs("controlled-line-repl"))) {
            LineResponse response = session.request("stderr-burst");

            assertEquals("response:stderr-burst", response.text());
            assertTrue(eventuallyTranscriptTruncated(session));
        }
    }

    @Test
    void parallelRequestsAreSerialized() throws Exception {
        ResponseDecoder twoLineDecoder = reader -> List.of(reader.readLine(), reader.readLine());
        LineSessionScenario.Draft service = fixtureScenario().withResponseDecoder(twoLineDecoder);

        try (LineSession session =
                openLineSession(service, call -> call.withArgs("two-line-delay-repl", "--delay-millis=100"))) {
            ExecutorService executor = Executors.newCachedThreadPool();
            try {
                Future<LineResponse> first = executor.submit(() -> session.request("a"));
                Future<LineResponse> second = executor.submit(() -> session.request("b"));

                List<String> firstLines = first.get().lines();
                List<String> secondLines = second.get().lines();
                List<List<String>> responses = List.of(firstLines, secondLines);

                assertTrue(responses.contains(List.of("start:a", "end:a")));
                assertTrue(responses.contains(List.of("start:b", "end:b")));
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void requestTimeoutIncludesWaitingForSerializedAccess() throws Exception {
        CountDownLatch firstResponseStarted = new CountDownLatch(1);
        ResponseDecoder decoder = reader -> {
            String first = reader.readLine();
            firstResponseStarted.countDown();
            return List.of(first, reader.readLine());
        };
        LineSessionScenario.Draft service = fixtureScenario().withResponseDecoder(decoder);

        try (LineSession session =
                openLineSession(service, call -> call.withArgs("two-line-delay-repl", "--delay-millis=300"))) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            try {
                Future<LineResponse> first = executor.submit(() -> session.request("first", Duration.ofSeconds(2)));
                assertTrue(firstResponseStarted.await(1, TimeUnit.SECONDS));

                long started = System.nanoTime();
                LineSessionException timeout = assertThrows(
                        LineSessionException.class, () -> session.request("queued", Duration.ofMillis(50)));
                Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

                assertEquals(LineSessionException.Reason.TIMEOUT, timeout.reason());
                assertTrue(elapsed.compareTo(Duration.ofMillis(250)) < 0, () -> "queued timeout took " + elapsed);
                assertFalse(session.onExit().isDone());
                assertEquals(List.of("start:first", "end:first"), first.get().lines());
                assertEquals(
                        List.of("start:after", "end:after"),
                        session.request("after", Duration.ofSeconds(2)).lines());
            } finally {
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
        }
    }

    @Test
    void nonCooperativeRequestEncodingCannotBlockCallerPastDeadline() throws Exception {
        BlockingUtf8Charset charset = new BlockingUtf8Charset();
        LineSessionScenario.Draft service = fixtureScenario().withCharset(charset);

        try (LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl"))) {
            ExecutorService executor = Executors.newSingleThreadExecutor();
            long started = System.nanoTime();
            try {
                Future<Throwable> request =
                        executor.submit(() -> captureFailure(() -> session.request("first", Duration.ofMillis(50))));
                assertTrue(charset.awaitEncoderStarted());

                Throwable failure = request.get(500, TimeUnit.MILLISECONDS);
                Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

                assertTrue(failure instanceof LineSessionException);
                assertEquals(LineSessionException.Reason.TIMEOUT, ((LineSessionException) failure).reason());
                assertTrue(elapsed.compareTo(Duration.ofMillis(400)) < 0, () -> "encoding timeout took " + elapsed);
            } finally {
                charset.releaseEncoder();
                assertTrue(charset.awaitEncoderFinished());
                assertTrue(charset.awaitEncoderTaskStopped());
                executor.shutdownNow();
                assertTrue(executor.awaitTermination(1, TimeUnit.SECONDS));
            }
            assertFalse(session.onExit().isDone());
            assertEquals(
                    "response:second",
                    session.request("second", Duration.ofSeconds(1)).text());
        }
    }

    @Test
    void invalidRequestTimeoutIsRejectedBeforeEncoding() {
        CountingUtf8Charset charset = new CountingUtf8Charset(Duration.ZERO);
        LineSessionScenario.Draft service = fixtureScenario().withCharset(charset);

        try (LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl"))) {
            assertThrows(IllegalArgumentException.class, () -> session.request("hello", Duration.ZERO));

            assertEquals(0, charset.encoderCreations());
        }
    }

    @Test
    void decoderCannotReturnSuccessAfterRequestDeadline() {
        ResponseDecoder decoder = reader -> {
            String line = reader.readLine();
            sleep(Duration.ofMillis(150));
            return List.of(line);
        };
        LineSessionScenario.Draft service = fixtureScenario().withResponseDecoder(decoder);

        try (LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl"))) {
            LineSessionException timeout =
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofMillis(50)));

            assertEquals(LineSessionException.Reason.TIMEOUT, timeout.reason());
        }
    }

    @Test
    void nonCooperativeDecoderCannotBlockCallerPastRequestDeadline() throws Exception {
        CountDownLatch decoderStarted = new CountDownLatch(1);
        CountDownLatch releaseDecoder = new CountDownLatch(1);
        CountDownLatch decoderFinished = new CountDownLatch(1);
        AtomicReference<Thread> decoderThread = new AtomicReference<>();
        ResponseDecoder decoder = reader -> {
            decoderThread.set(Thread.currentThread());
            try {
                String line = reader.readLine();
                decoderStarted.countDown();
                awaitIgnoringInterrupts(releaseDecoder);
                return List.of(line);
            } finally {
                decoderFinished.countDown();
            }
        };
        LineSessionScenario.Draft service = fixtureScenario().withResponseDecoder(decoder);

        try (LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl"))) {
            long started = System.nanoTime();
            LineSessionException timeout =
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofMillis(500)));
            Duration elapsed = Duration.ofNanos(System.nanoTime() - started);

            assertEquals(LineSessionException.Reason.TIMEOUT, timeout.reason());
            assertTrue(elapsed.compareTo(Duration.ofMillis(1500)) < 0, () -> "decoder timeout took " + elapsed);
            assertEquals(0, decoderStarted.getCount());
        } finally {
            releaseDecoder.countDown();
            assertTrue(decoderFinished.await(1, TimeUnit.SECONDS));
            assertTaskStopped(decoderThread.get(), "line response decoder");
        }
    }

    private static LineSessionScenario.Draft fixtureScenario() {
        return Procwright.command(TestCliSupport.command()).lineSession();
    }

    private static boolean eventuallyTranscriptTruncated(LineSession session) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (session.transcript().truncated()) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }

    private static boolean eventuallyTranscriptContains(LineSession session, String expected)
            throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (session.transcript().text().contains(expected)) {
                return true;
            }
            Thread.sleep(10);
        }
        return session.transcript().text().contains(expected);
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

    private static boolean awaitMalformedTranscript(LineSession session) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(2).toNanos();
        while (System.nanoTime() < deadline) {
            if (session.transcript().malformed()) {
                return true;
            }
            Thread.sleep(10);
        }
        return session.transcript().malformed();
    }

    private static void sleep(Duration duration) {
        try {
            TimeUnit.NANOSECONDS.sleep(duration.toNanos());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", exception);
        }
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

    private static Throwable captureFailure(Runnable operation) {
        try {
            operation.run();
            return null;
        } catch (Throwable failure) {
            return failure;
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

    private static Duration timeoutAfterFixtureStartup() {
        return isWindows() ? Duration.ofSeconds(2) : Duration.ofSeconds(1);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static LineSession openLineSession(
            LineSessionScenario.Draft scenario, UnaryOperator<LineSessionScenario.Draft> configure) {
        return configure.apply(scenario).open();
    }

    private static final class IndexedNewDecoderFailureCharset extends Charset {

        private final int failingCreation;
        private final RuntimeException failure;
        private int decoderCreations;

        private IndexedNewDecoderFailureCharset(int failingCreation, RuntimeException failure) {
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

        private int decoderCreations() {
            return decoderCreations;
        }
    }

    private static final class MarkerRuntimeFailureCharset extends Charset {

        private final byte marker;
        private final RuntimeException failure;

        private MarkerRuntimeFailureCharset(byte marker, RuntimeException failure) {
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

        private RuntimeFailureOnFlushCharset(RuntimeException failure) {
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

    private static final class CountingUtf8Charset extends Charset {

        private final Duration firstEncoderDelay;
        private final AtomicBoolean delayPending = new AtomicBoolean(true);
        private final AtomicInteger encoderCreations = new AtomicInteger();

        private CountingUtf8Charset(Duration firstEncoderDelay) {
            super("X-Procwright-Line-Counting-UTF-8", new String[0]);
            this.firstEncoderDelay = firstEncoderDelay;
        }

        @Override
        public boolean contains(Charset charset) {
            return StandardCharsets.UTF_8.contains(charset);
        }

        @Override
        public CharsetDecoder newDecoder() {
            return StandardCharsets.UTF_8.newDecoder();
        }

        @Override
        public CharsetEncoder newEncoder() {
            encoderCreations.incrementAndGet();
            if (delayPending.compareAndSet(true, false) && !firstEncoderDelay.isZero()) {
                sleep(firstEncoderDelay);
            }
            return StandardCharsets.UTF_8.newEncoder();
        }

        private int encoderCreations() {
            return encoderCreations.get();
        }
    }

    private static final class BlockingUtf8Charset extends Charset {

        private final AtomicBoolean blockNextEncoder = new AtomicBoolean(true);
        private final CountDownLatch encoderStarted = new CountDownLatch(1);
        private final CountDownLatch releaseEncoder = new CountDownLatch(1);
        private final CountDownLatch encoderFinished = new CountDownLatch(1);
        private volatile Thread encoderThread;

        private BlockingUtf8Charset() {
            super("X-Procwright-Line-Blocking-UTF-8", new String[0]);
        }

        @Override
        public boolean contains(Charset charset) {
            return StandardCharsets.UTF_8.contains(charset);
        }

        @Override
        public CharsetDecoder newDecoder() {
            return StandardCharsets.UTF_8.newDecoder();
        }

        @Override
        public CharsetEncoder newEncoder() {
            if (blockNextEncoder.compareAndSet(true, false)) {
                encoderThread = Thread.currentThread();
                encoderStarted.countDown();
                try {
                    awaitIgnoringInterrupts(releaseEncoder);
                } finally {
                    encoderFinished.countDown();
                }
            }
            return StandardCharsets.UTF_8.newEncoder();
        }

        private boolean awaitEncoderStarted() throws InterruptedException {
            return encoderStarted.await(1, TimeUnit.SECONDS);
        }

        private void releaseEncoder() {
            releaseEncoder.countDown();
        }

        private boolean awaitEncoderFinished() throws InterruptedException {
            return encoderFinished.await(1, TimeUnit.SECONDS);
        }

        private boolean awaitEncoderTaskStopped() throws InterruptedException {
            Thread task = encoderThread;
            if (task == null) {
                return false;
            }
            task.join(TimeUnit.SECONDS.toMillis(1));
            return !task.isAlive();
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
