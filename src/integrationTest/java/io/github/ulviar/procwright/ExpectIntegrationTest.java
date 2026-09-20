/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.Expect;
import io.github.ulviar.procwright.session.ExpectException;
import io.github.ulviar.procwright.session.ExpectTranscriptValues;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

final class ExpectIntegrationTest {

    @Test
    void literalMatchAndSendLineRecordOrder() {
        try (Expect expect = fixtureService()
                .interactive()
                .expect()
                .withArgs("line-repl", "--prompt=ready> ", "--response-prefix=echo:")
                .withTimeout(Duration.ofSeconds(1))
                .open()) {
            expect.expectText("ready> ");
            expect.sendLine("hello");
            expect.expectText("echo:hello");

            String transcript = expect.transcript().text();
            assertTrue(transcript.indexOf("expect text: <redacted>") < transcript.indexOf("send line: <redacted>"));
            assertTrue(transcript.contains("send line: <redacted>"));
            assertTrue(!transcript.contains("send line: hello"));
            assertTrue(transcript.contains("stdout: echo:hello"));
        }
    }

    @Test
    void literalMatchResultReturnsMatchedTextEmptyGroupsAndBeforeText() {
        try (Expect expect = fixtureService()
                .interactive()
                .expect()
                .withArgs("line-repl", "--prompt=ready> ", "--response-prefix=echo:")
                .withTimeout(Duration.ofSeconds(1))
                .open()) {
            io.github.ulviar.procwright.session.ExpectMatch match = expect.expectTextMatch("dy> ");

            assertEquals("dy> ", match.matched());
            assertEquals(java.util.List.of(), match.groups());
            assertEquals("rea", match.before());
        }
    }

    @Test
    void regexMatchResultExtractsValueThroughCaptureGroups() {
        try (Expect expect = fixtureService()
                .interactive()
                .expect()
                .withArgs("line-repl", "--prompt=ready> ", "--response-prefix=echo:")
                .withTimeout(Duration.ofSeconds(1))
                .open()) {
            expect.expectText("ready> ");
            expect.sendLine("token-42");

            io.github.ulviar.procwright.session.ExpectMatch match =
                    expect.expectRegexMatch(Pattern.compile("echo:(token)-(\\d+)"));

            assertEquals("echo:token-42", match.matched());
            assertEquals(java.util.List.of("token", "42"), match.groups());
        }
    }

    @Test
    void consecutiveMatchResultsReportBeforeTextBetweenMatches() {
        try (Expect expect = fixtureService()
                .interactive()
                .expect()
                .withArgs("line-repl", "--prompt=ready> ", "--response-prefix=echo:")
                .withTimeout(Duration.ofSeconds(1))
                .open()) {
            expect.expectText("ready> ");
            expect.sendLine("alpha");

            io.github.ulviar.procwright.session.ExpectMatch match = expect.expectTextMatch("alpha");

            assertEquals("echo:", match.before());
        }
    }

    @Test
    void expectMatchesCrlfTerminatedOutputWithoutNormalization() {
        try (Expect expect = fixtureService()
                .interactive()
                .expect()
                .withArgs("line-repl", "--crlf=true", "--response-prefix=echo:")
                .withTimeout(Duration.ofSeconds(2))
                .open()) {
            expect.sendLine("alpha");

            io.github.ulviar.procwright.session.ExpectMatch match =
                    expect.expectRegexMatch(Pattern.compile("echo:(\\w+)\r\n"));

            assertEquals("echo:alpha\r\n", match.matched());
            assertEquals(java.util.List.of("alpha"), match.groups());
        }
    }

    @Test
    void matchResultTimeoutIsTypedExpectException() {
        try (Expect expect = fixtureService()
                .interactive()
                .expect()
                .withArgs("partial", "--stdout=", "--stderr=partial-error", "--hold-millis=5000")
                .withTimeout(timeoutAfterFixtureStartup())
                .open()) {
            ExpectException exception =
                    assertThrows(ExpectException.class, () -> expect.expectTextMatch("never-appears"));

            assertEquals(ExpectException.Reason.TIMEOUT, exception.reason());
        }
    }

    @Test
    void transcriptValuesAreOptIn() {
        try (Expect expect = fixtureService()
                .interactive()
                .expect()
                .withArgs("line-repl", "--prompt=ready> ", "--response-prefix=echo:")
                .withTimeout(Duration.ofSeconds(1))
                .withTranscriptValues(ExpectTranscriptValues.VERBATIM)
                .open()) {
            expect.expectText("ready> ");
            expect.sendLine("hello");
            expect.expectText("echo:hello");

            String transcript = expect.transcript().text();
            assertTrue(transcript.contains("expect text: ready> "));
            assertTrue(transcript.contains("send line: hello"));
        }
    }

    @Test
    void regexMatchWorksAcrossPromptOutput() {
        try (Expect expect = fixtureService()
                .interactive()
                .expect()
                .withArgs("line-repl", "--prompt=ready> ", "--response-prefix=echo:")
                .withTimeout(Duration.ofSeconds(1))
                .open()) {
            expect.expectRegex(Pattern.compile("ready>\\s*$"));
        }
    }

    @Test
    void sendWritesRawText() {
        try (Expect expect = fixtureService()
                .interactive()
                .expect()
                .withArgs("line-repl", "--prompt=ready> ", "--response-prefix=echo:")
                .withTimeout(Duration.ofSeconds(1))
                .open()) {
            expect.expectText("ready> ");
            expect.send("raw\n");
            expect.expectText("echo:raw");
        }
    }

    @Test
    void sendLineRejectsEmbeddedLineSeparators() {
        try (Expect expect = fixtureService()
                .interactive()
                .expect()
                .withArgs("line-repl", "--prompt=ready> ", "--response-prefix=echo:")
                .withTimeout(Duration.ofSeconds(1))
                .open()) {
            assertThrows(IllegalArgumentException.class, () -> expect.sendLine("a\nb"));
            assertThrows(IllegalArgumentException.class, () -> expect.sendLine("a\rb"));
        }
    }

    @Test
    void closingExpectClosesItsProcess() throws Exception {
        Expect expect = fixtureService()
                .interactive()
                .expect()
                .withArgs("sleep", "--millis=5000", "--finished=false")
                .withTimeout(Duration.ofSeconds(1))
                .open();

        expect.close();

        assertTrue(expect.onExit()
                .get(2, java.util.concurrent.TimeUnit.SECONDS)
                .exitCode()
                .isPresent());
    }

    @Test
    void timeoutRedactsExpectedTextInTranscriptAndMessageByDefault() {
        try (Expect expect = fixtureService()
                .interactive()
                .expect()
                .withArgs("partial", "--stdout=", "--stderr=partial-error", "--hold-millis=5000")
                .withTimeout(timeoutAfterFixtureStartup())
                .open()) {
            ExpectException exception = assertThrows(ExpectException.class, () -> expect.expectText("secret-done"));

            assertEquals(ExpectException.Reason.TIMEOUT, exception.reason());
            assertTrue(exception.getMessage().contains("<redacted>"));
            assertFalse(exception.getMessage().contains("secret-done"));
            assertTrue(exception.transcript().text().contains("expect text: <redacted>"));
            assertFalse(exception.transcript().text().contains("expect text: secret-done"));
            assertTrue(exception.transcript().text().contains("stderr: partial-error"));
        }
    }

    @Test
    void eofRedactsExpectedRegexInMessageByDefault() {
        Pattern secretPattern = Pattern.compile("secret-never");

        try (Expect expect = fixtureService()
                .interactive()
                .expect()
                .withArgs("exit")
                .withTimeout(Duration.ofSeconds(1))
                .open()) {
            ExpectException exception = assertThrows(ExpectException.class, () -> expect.expectRegex(secretPattern));

            assertEquals(ExpectException.Reason.EOF, exception.reason());
            assertTrue(exception.getMessage().contains("<redacted>"));
            assertFalse(exception.getMessage().contains("secret-never"));
            assertFalse(exception.transcript().text().contains("secret-never"));
        }
    }

    @Test
    void verbatimTranscriptValuesAllowExpectedTextInFailureMessage() {
        try (Expect expect = fixtureService()
                .interactive()
                .expect()
                .withArgs("partial", "--stdout=", "--stderr=partial-error", "--hold-millis=5000")
                .withTimeout(Duration.ofMillis(100))
                .withTranscriptValues(ExpectTranscriptValues.VERBATIM)
                .open()) {
            ExpectException exception = assertThrows(ExpectException.class, () -> expect.expectText("visible-done"));

            assertEquals(ExpectException.Reason.TIMEOUT, exception.reason());
            assertTrue(exception.getMessage().contains("visible-done"));
            assertTrue(exception.transcript().text().contains("expect text: visible-done"));
        }
    }

    @Test
    void matchBufferIsBoundedIndependentlyFromTranscript() {
        try (Expect expect = fixtureService()
                .interactive()
                .expect()
                .withArgs("controlled-line-repl")
                .withTimeout(Duration.ofSeconds(1))
                .withMatchBufferLimit(16)
                .withTranscriptLimit(256)
                .open()) {
            expect.sendLine("many");
            expect.expectText("done");

            assertTrue(expect.transcript().text().contains("done"));
        }
    }

    @Test
    void transcriptIsBounded() {
        try (Expect expect = fixtureService()
                .interactive()
                .expect()
                .withArgs("controlled-line-repl")
                .withTimeout(Duration.ofSeconds(1))
                .withTranscriptLimit(80)
                .open()) {
            expect.sendLine("many");
            expect.expectText("done");

            assertTrue(expect.transcript().truncated());
            assertTrue(expect.transcript().text().contains("done"));
        }
    }

    @Test
    void eofBeforeExpectedOutputIsDistinct() {
        try (Expect expect = fixtureService()
                .interactive()
                .expect()
                .withArgs("exit")
                .withTimeout(Duration.ofSeconds(1))
                .open()) {
            ExpectException exception = assertThrows(ExpectException.class, () -> expect.expectText("never"));

            assertEquals(ExpectException.Reason.EOF, exception.reason());
            assertEquals(
                    0,
                    expect.onExit()
                            .orTimeout(1, TimeUnit.SECONDS)
                            .join()
                            .exitCode()
                            .orElseThrow());
        }
    }

    @Test
    void ansiControlSequenceStrippingNormalizesOutputBeforeMatching() {
        try (Expect expect = fixtureService()
                .interactive()
                .expect()
                .withArgs("ansi-prompt")
                .withTimeout(Duration.ofSeconds(1))
                .withAnsiControlSequenceStripping()
                .open()) {
            expect.expectText("READY> ");
            assertFalse(expect.transcript().text().contains("\u001B"));
        }
    }

    @Test
    void expectUsesConfiguredCharsetForInputAndOutput() {
        ExpectScenario.Draft scenario = Procwright.command(TestCliSupport.command())
                .interactive()
                .expect()
                .withCharset(StandardCharsets.ISO_8859_1);

        try (Expect expect = scenario.withArgs(
                        "line-repl", "--charset=ISO-8859-1", "--prompt=ready> ", "--response-prefix=echo:")
                .withTimeout(Duration.ofSeconds(2))
                .open()) {
            expect.expectText("ready> ");
            expect.sendLine("café");
            expect.expectText("echo:café");
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void expectCanMatchFinalOutputAfterProcessExit(boolean regex) throws Exception {
        try (Expect expect = fixtureService()
                .interactive()
                .expect()
                .withArgs("stdin-echo", "--prefix=final:")
                .withTimeout(Duration.ofSeconds(2))
                .open()) {
            expect.send("payload");
            expect.closeStdin();
            expect.closeStdin();

            assertEquals(0, expect.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
            if (regex) {
                expect.expectRegex(Pattern.compile("final:payload"));
            } else {
                expect.expectText("final:payload");
            }
            assertEquals(
                    ExpectException.Reason.EOF,
                    assertThrows(ExpectException.class, () -> expect.expectText("missing"))
                            .reason());
        }
    }

    @Test
    void expectCanDecodeOutputWithACharsetDifferentFromInput() {
        try (Expect expect = fixtureService()
                .interactive()
                .expect()
                .withArgs(
                        "line-repl",
                        "--input-charset=ISO-8859-1",
                        "--output-charset=UTF-16LE",
                        "--prompt=ready> ",
                        "--response-prefix=echo:")
                .withCharset(StandardCharsets.ISO_8859_1)
                .withOutputCharset(StandardCharsets.UTF_16LE)
                .withTimeout(Duration.ofSeconds(2))
                .open()) {
            expect.expectText("ready> ");
            expect.sendLine("café");
            expect.expectText("echo:café");
        }
    }

    private static CommandService fixtureService() {
        return Procwright.command(TestCliSupport.command());
    }

    private static Duration timeoutAfterFixtureStartup() {
        return isWindows() ? Duration.ofSeconds(2) : Duration.ofSeconds(1);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
