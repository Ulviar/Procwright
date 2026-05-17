package com.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

final class ExpectIntegrationTest {

    @Test
    void literalMatchAndSendLineRecordOrder() {
        try (Session session = fixtureService().interactive(call -> call.args("prompt-repl"));
                Expect expect = session.expect(ExpectOptions.defaults().withTimeout(Duration.ofSeconds(1)))) {
            expect.expectText("ready> ");
            expect.sendLine("hello");
            expect.expectText("echo:hello");

            String transcript = expect.transcript().text();
            assertTrue(transcript.indexOf("expect text: ready> ") < transcript.indexOf("send line: hello"));
            assertTrue(transcript.contains("stdout: echo:hello"));
        }
    }

    @Test
    void regexMatchWorksAcrossPromptOutput() {
        try (Session session = fixtureService().interactive(call -> call.args("prompt-repl"));
                Expect expect = session.expect(ExpectOptions.defaults().withTimeout(Duration.ofSeconds(1)))) {
            expect.expectRegex(Pattern.compile("ready>\\s*$"));
        }
    }

    @Test
    void sendWritesRawText() {
        try (Session session = fixtureService().interactive(call -> call.args("prompt-repl"));
                Expect expect = session.expect(ExpectOptions.defaults().withTimeout(Duration.ofSeconds(1)))) {
            expect.expectText("ready> ");
            expect.send("raw\n");
            expect.expectText("echo:raw");
        }
    }

    @Test
    void sendLineRejectsEmbeddedLineSeparators() {
        try (Session session = fixtureService().interactive(call -> call.args("prompt-repl"));
                Expect expect = session.expect(ExpectOptions.defaults().withTimeout(Duration.ofSeconds(1)))) {
            assertThrows(IllegalArgumentException.class, () -> expect.sendLine("a\nb"));
            assertThrows(IllegalArgumentException.class, () -> expect.sendLine("a\rb"));
        }
    }

    @Test
    void sessionOutputCanHaveOnlyOneExpectOwner() {
        try (Session session = fixtureService().interactive(call -> call.args("prompt-repl"));
                Expect expect = session.expect(ExpectOptions.defaults().withTimeout(Duration.ofSeconds(1)))) {
            assertThrows(IllegalStateException.class, () -> session.expect());
        }
    }

    @Test
    void rawOutputStreamsCannotBeReadAfterExpectClaimsOutputOwnership() throws Exception {
        try (Session session = fixtureService().interactive(call -> call.args("prompt-repl"))) {
            InputStream stdout = session.stdout();
            InputStream stderr = session.stderr();

            try (Expect expect = session.expect(ExpectOptions.defaults().withTimeout(Duration.ofSeconds(1)))) {
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
        try (Session session = fixtureService().interactive(call -> call.args("prompt-repl"));
                Expect expect = session.expect(ExpectOptions.defaults().withTimeout(Duration.ofSeconds(1)))) {
            assertThrows(IllegalStateException.class, session.stdout()::read);
            assertThrows(IllegalStateException.class, session.stderr()::read);
            expect.expectText("ready> ");
        }
    }

    @Test
    void closingExpectClosesUnderlyingSession() throws Exception {
        Session session = fixtureService().interactive(call -> call.args("sleep", "5000"));
        Expect expect = session.expect(ExpectOptions.defaults().withTimeout(Duration.ofSeconds(1)));

        expect.close();

        assertTrue(session.onExit()
                .get(2, java.util.concurrent.TimeUnit.SECONDS)
                .exitCode()
                .isPresent());
    }

    @Test
    void timeoutIncludesTranscriptAndExpectedText() {
        try (Session session = fixtureService().interactive(call -> call.args("partial-stderr-sleep", "5000"));
                Expect expect = session.expect(ExpectOptions.defaults().withTimeout(Duration.ofMillis(100)))) {
            ExpectException exception = assertThrows(ExpectException.class, () -> expect.expectText("done"));

            assertEquals(ExpectException.Reason.TIMEOUT, exception.reason());
            assertTrue(exception.transcript().text().contains("expect text: done"));
            assertTrue(exception.transcript().text().contains("stderr: partial-error"));
        }
    }

    @Test
    void stderrFilterFailureIsDistinct() {
        ExpectOptions options = ExpectOptions.defaults()
                .withTimeout(Duration.ofSeconds(1))
                .withOutputFilter(output -> {
                    if (output.contains("partial-error")) {
                        throw new IllegalArgumentException("bad stderr");
                    }
                    return output;
                });

        try (Session session = fixtureService().interactive(call -> call.args("partial-stderr-sleep", "5000"));
                Expect expect = session.expect(options)) {
            ExpectException exception = assertThrows(ExpectException.class, () -> expect.expectText("done"));

            assertEquals(ExpectException.Reason.FAILURE, exception.reason());
        }
    }

    @Test
    void filterFailureIsDistinct() {
        ExpectOptions options = ExpectOptions.defaults()
                .withTimeout(Duration.ofSeconds(1))
                .withOutputFilter(output -> {
                    throw new IllegalArgumentException("bad output");
                });

        try (Session session = fixtureService().interactive(call -> call.args("prompt-repl"));
                Expect expect = session.expect(options)) {
            ExpectException exception = assertThrows(ExpectException.class, () -> expect.expectText("ready"));

            assertEquals(ExpectException.Reason.FAILURE, exception.reason());
            assertTrue(exception.transcript().text().contains("expect text: ready"));
        }
    }

    @Test
    void matchBufferIsBoundedIndependentlyFromTranscript() {
        ExpectOptions options = ExpectOptions.defaults()
                .withTimeout(Duration.ofSeconds(1))
                .withMatchBufferLimit(16)
                .withTranscriptLimit(256);

        try (Session session = fixtureService().interactive(call -> call.args("line-repl"));
                Expect expect = session.expect(options)) {
            expect.sendLine("many");
            expect.expectText("done");

            assertTrue(expect.transcript().text().contains("done"));
        }
    }

    @Test
    void transcriptIsBounded() {
        ExpectOptions options =
                ExpectOptions.defaults().withTimeout(Duration.ofSeconds(1)).withTranscriptLimit(80);

        try (Session session = fixtureService().interactive(call -> call.args("line-repl"));
                Expect expect = session.expect(options)) {
            expect.sendLine("many");
            expect.expectText("done");

            assertTrue(expect.transcript().truncated());
            assertTrue(expect.transcript().text().contains("done"));
        }
    }

    @Test
    void eofBeforeExpectedOutputIsDistinct() {
        try (Session session = fixtureService().interactive(call -> call.args("exit-now"));
                Expect expect = session.expect(ExpectOptions.defaults().withTimeout(Duration.ofSeconds(1)))) {
            ExpectException exception = assertThrows(ExpectException.class, () -> expect.expectText("never"));

            assertEquals(ExpectException.Reason.EOF, exception.reason());
        }
    }

    @Test
    void ansiFilterCanNormalizeOutputBeforeMatching() {
        ExpectOptions options = ExpectOptions.defaults()
                .withTimeout(Duration.ofSeconds(1))
                .withOutputFilter(ExpectOutputFilter.stripAnsiControlSequences());

        try (Session session = fixtureService().interactive(call -> call.args("ansi-prompt"));
                Expect expect = session.expect(options)) {
            expect.expectText("READY> ");
        }
    }

    private static CommandService fixtureService() {
        CommandSpec command = CommandSpec.builder(javaExecutable())
                .args("-cp", System.getProperty("java.class.path"), ProcessFixtureProgram.class.getName())
                .build();
        return new CommandService(command, RunOptions.defaults());
    }

    private static String javaExecutable() {
        String executableName = isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executableName).toString();
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
