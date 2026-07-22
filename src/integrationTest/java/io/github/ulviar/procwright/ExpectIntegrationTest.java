/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.Expect;
import io.github.ulviar.procwright.session.ExpectException;
import io.github.ulviar.procwright.session.ExpectTranscriptValues;
import io.github.ulviar.procwright.session.Session;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class ExpectIntegrationTest {

    @Test
    void literalMatchAndSendLineRecordOrder() {
        try (Session session = fixtureService()
                        .interactive()
                        .withArgs("line-repl", "--prompt=ready> ", "--response-prefix=echo:")
                        .open();
                Expect expect =
                        session.expect().withTimeout(Duration.ofSeconds(1)).open()) {
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
        try (Session session = fixtureService()
                        .interactive()
                        .withArgs("line-repl", "--prompt=ready> ", "--response-prefix=echo:")
                        .open();
                Expect expect =
                        session.expect().withTimeout(Duration.ofSeconds(1)).open()) {
            io.github.ulviar.procwright.session.ExpectMatch match = expect.expectTextMatch("dy> ");

            assertEquals("dy> ", match.matched());
            assertEquals(java.util.List.of(), match.groups());
            assertEquals("rea", match.before());
        }
    }

    @Test
    void regexMatchResultExtractsValueThroughCaptureGroups() {
        try (Session session = fixtureService()
                        .interactive()
                        .withArgs("line-repl", "--prompt=ready> ", "--response-prefix=echo:")
                        .open();
                Expect expect =
                        session.expect().withTimeout(Duration.ofSeconds(1)).open()) {
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
        try (Session session = fixtureService()
                        .interactive()
                        .withArgs("line-repl", "--prompt=ready> ", "--response-prefix=echo:")
                        .open();
                Expect expect =
                        session.expect().withTimeout(Duration.ofSeconds(1)).open()) {
            expect.expectText("ready> ");
            expect.sendLine("alpha");

            io.github.ulviar.procwright.session.ExpectMatch match = expect.expectTextMatch("alpha");

            assertEquals("echo:", match.before());
        }
    }

    @Test
    void expectMatchesCrlfTerminatedOutputWithoutNormalization() {
        try (Session session = fixtureService()
                        .interactive()
                        .withArgs("line-repl", "--crlf=true", "--response-prefix=echo:")
                        .open();
                Expect expect =
                        session.expect().withTimeout(Duration.ofSeconds(2)).open()) {
            expect.sendLine("alpha");

            io.github.ulviar.procwright.session.ExpectMatch match =
                    expect.expectRegexMatch(Pattern.compile("echo:(\\w+)\r\n"));

            assertEquals("echo:alpha\r\n", match.matched());
            assertEquals(java.util.List.of("alpha"), match.groups());
        }
    }

    @Test
    void matchResultTimeoutIsTypedExpectException() {
        try (Session session = fixtureService()
                        .interactive()
                        .withArgs("partial", "--stdout=", "--stderr=partial-error", "--hold-millis=5000")
                        .open();
                Expect expect = session.expect()
                        .withTimeout(timeoutAfterFixtureStartup())
                        .open()) {
            ExpectException exception =
                    assertThrows(ExpectException.class, () -> expect.expectTextMatch("never-appears"));

            assertEquals(ExpectException.Reason.TIMEOUT, exception.reason());
        }
    }

    @Test
    void transcriptValuesAreOptIn() {
        try (Session session = fixtureService()
                        .interactive()
                        .withArgs("line-repl", "--prompt=ready> ", "--response-prefix=echo:")
                        .open();
                Expect expect = session.expect()
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
        try (Session session = fixtureService()
                        .interactive()
                        .withArgs("line-repl", "--prompt=ready> ", "--response-prefix=echo:")
                        .open();
                Expect expect =
                        session.expect().withTimeout(Duration.ofSeconds(1)).open()) {
            expect.expectRegex(Pattern.compile("ready>\\s*$"));
        }
    }

    @Test
    void sendWritesRawText() {
        try (Session session = fixtureService()
                        .interactive()
                        .withArgs("line-repl", "--prompt=ready> ", "--response-prefix=echo:")
                        .open();
                Expect expect =
                        session.expect().withTimeout(Duration.ofSeconds(1)).open()) {
            expect.expectText("ready> ");
            expect.send("raw\n");
            expect.expectText("echo:raw");
        }
    }

    @Test
    void sendLineRejectsEmbeddedLineSeparators() {
        try (Session session = fixtureService()
                        .interactive()
                        .withArgs("line-repl", "--prompt=ready> ", "--response-prefix=echo:")
                        .open();
                Expect expect =
                        session.expect().withTimeout(Duration.ofSeconds(1)).open()) {
            assertThrows(IllegalArgumentException.class, () -> expect.sendLine("a\nb"));
            assertThrows(IllegalArgumentException.class, () -> expect.sendLine("a\rb"));
        }
    }

    @Test
    void sessionOutputCanHaveOnlyOneExpectOwner() {
        try (Session session = fixtureService()
                        .interactive()
                        .withArgs("line-repl", "--prompt=ready> ", "--response-prefix=echo:")
                        .open();
                Expect expect =
                        session.expect().withTimeout(Duration.ofSeconds(1)).open()) {
            expect.expectText("ready> ");
            assertThrows(IllegalStateException.class, () -> session.expect().open());
        }
    }

    @Test
    void rawOutputStreamsCannotBeReadAfterExpectClaimsOutputOwnership() throws Exception {
        try (Session session = fixtureService()
                .interactive()
                .withArgs("line-repl", "--prompt=ready> ", "--response-prefix=echo:")
                .open()) {
            InputStream stdout = session.stdout();
            InputStream stderr = session.stderr();

            try (Expect expect =
                    session.expect().withTimeout(Duration.ofSeconds(1)).open()) {
                assertThrows(IllegalStateException.class, stdout::read);
                assertThrows(IllegalStateException.class, stderr::read);
                assertThrows(IllegalStateException.class, stdout::close);
                assertThrows(IllegalStateException.class, stderr::close);
                expect.expectText("ready> ");
            }
        }
    }

    @Test
    void newRawOutputStreamsCannotBeReadAfterExpectClaimsOutputOwnership() throws Exception {
        try (Session session = fixtureService()
                        .interactive()
                        .withArgs("line-repl", "--prompt=ready> ", "--response-prefix=echo:")
                        .open();
                Expect expect =
                        session.expect().withTimeout(Duration.ofSeconds(1)).open()) {
            assertThrows(IllegalStateException.class, session.stdout()::read);
            assertThrows(IllegalStateException.class, session.stderr()::read);
            expect.expectText("ready> ");
        }
    }

    @Test
    void closingExpectClosesUnderlyingSession() throws Exception {
        Session session = fixtureService()
                .interactive()
                .withArgs("sleep", "--millis=5000", "--finished=false")
                .open();
        Expect expect = session.expect().withTimeout(Duration.ofSeconds(1)).open();

        expect.close();

        assertTrue(session.onExit()
                .get(2, java.util.concurrent.TimeUnit.SECONDS)
                .exitCode()
                .isPresent());
    }

    @Test
    void closingExpectDoesNotReturnItsOutputStreamsToRawSessionCode() throws Exception {
        Session session = fixtureService()
                .interactive()
                .withArgs("sleep", "--millis=5000", "--finished=false")
                .open();
        InputStream stdout = session.stdout();
        InputStream stderr = session.stderr();
        Expect expect = session.expect().withTimeout(Duration.ofSeconds(1)).open();

        expect.close();
        session.onExit().get(2, java.util.concurrent.TimeUnit.SECONDS);

        assertThrows(IllegalStateException.class, stdout::read);
        assertThrows(IllegalStateException.class, stderr::read);
        assertThrows(IllegalStateException.class, stdout::close);
        assertThrows(IllegalStateException.class, stderr::close);
        assertThrows(IllegalStateException.class, () -> session.expect().open());
    }

    @Test
    void timeoutRedactsExpectedTextInTranscriptAndMessageByDefault() {
        try (Session session = fixtureService()
                        .interactive()
                        .withArgs("partial", "--stdout=", "--stderr=partial-error", "--hold-millis=5000")
                        .open();
                Expect expect = session.expect()
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

        try (Session session = fixtureService().interactive().withArgs("exit").open();
                Expect expect =
                        session.expect().withTimeout(Duration.ofSeconds(1)).open()) {
            ExpectException exception = assertThrows(ExpectException.class, () -> expect.expectRegex(secretPattern));

            assertEquals(ExpectException.Reason.EOF, exception.reason());
            assertTrue(exception.getMessage().contains("<redacted>"));
            assertFalse(exception.getMessage().contains("secret-never"));
            assertFalse(exception.transcript().text().contains("secret-never"));
        }
    }

    @Test
    void verbatimTranscriptValuesAllowExpectedTextInFailureMessage() {
        try (Session session = fixtureService()
                        .interactive()
                        .withArgs("partial", "--stdout=", "--stderr=partial-error", "--hold-millis=5000")
                        .open();
                Expect expect = session.expect()
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
        try (Session session = fixtureService()
                        .interactive()
                        .withArgs("controlled-line-repl")
                        .open();
                Expect expect = session.expect()
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
        try (Session session = fixtureService()
                        .interactive()
                        .withArgs("controlled-line-repl")
                        .open();
                Expect expect = session.expect()
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
        try (Session session = fixtureService().interactive().withArgs("exit").open();
                Expect expect =
                        session.expect().withTimeout(Duration.ofSeconds(1)).open()) {
            ExpectException exception = assertThrows(ExpectException.class, () -> expect.expectText("never"));

            assertEquals(ExpectException.Reason.EOF, exception.reason());
        }
    }

    @Test
    void ansiControlSequenceStrippingNormalizesOutputBeforeMatching() {
        try (Session session =
                        fixtureService().interactive().withArgs("ansi-prompt").open();
                Expect expect = session.expect()
                        .withTimeout(Duration.ofSeconds(1))
                        .withAnsiControlSequenceStripping()
                        .open()) {
            expect.expectText("READY> ");
            assertFalse(expect.transcript().text().contains("\u001B"));
        }
    }

    @Test
    void expectFollowsSessionCharsetByDefault() {
        InteractiveScenario.Draft scenario =
                Procwright.command(TestCliSupport.command()).interactive().withCharset(StandardCharsets.ISO_8859_1);

        try (Session session = scenario.withArgs(
                                "line-repl", "--charset=ISO-8859-1", "--prompt=ready> ", "--response-prefix=echo:")
                        .open();
                Expect expect =
                        session.expect().withTimeout(Duration.ofSeconds(2)).open()) {
            expect.expectText("ready> ");
            expect.sendLine("café");
            expect.expectText("echo:café");
        }
    }

    @Test
    void explicitExpectCharsetTakesPrecedenceOverSessionCharset() {
        InteractiveScenario.Draft scenario =
                Procwright.command(TestCliSupport.command()).interactive().withCharset(StandardCharsets.ISO_8859_1);

        try (Session session = scenario.withArgs(
                                "line-repl", "--charset=UTF-8", "--prompt=ready> ", "--response-prefix=echo:")
                        .open();
                Expect expect = session.expect()
                        .withTimeout(Duration.ofSeconds(2))
                        .withCharset(StandardCharsets.UTF_8)
                        .open()) {
            // ASCII traffic stays charset-neutral; this only pins the explicit-charset precedence contract.
            expect.expectText("ready> ");
            expect.sendLine("plain");
            expect.expectText("echo:plain");
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
