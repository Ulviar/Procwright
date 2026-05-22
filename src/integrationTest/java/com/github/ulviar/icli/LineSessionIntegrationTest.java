package com.github.ulviar.icli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.ulviar.icli.command.CommandExecutionException;
import com.github.ulviar.icli.command.RunOptions;
import com.github.ulviar.icli.session.LineResponse;
import com.github.ulviar.icli.session.LineSession;
import com.github.ulviar.icli.session.LineSessionException;
import com.github.ulviar.icli.session.LineSessionOptions;
import com.github.ulviar.icli.session.ResponseDecoder;
import com.github.ulviar.icli.session.SessionOptions;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class LineSessionIntegrationTest {

    @Test
    void requestSendsLineAndReadsDefaultResponse() {
        try (LineSession session = fixtureService().lineSession(call -> call.args("controlled-line-repl"))) {
            LineResponse response = session.request("hello");

            assertEquals(List.of("response:hello"), response.lines());
            assertEquals("response:hello", response.text());
            assertTrue(response.transcript().text().contains("stdout: response:hello"));
        }
    }

    @Test
    void readinessProbeRunsBeforeLineSessionIsReturned() {
        try (LineSession session = fixtureService().lineSession(call -> call.args("controlled-line-repl")
                .readiness(ready ->
                        assertEquals("response:healthy", ready.request("health").text()))
                .readinessTimeout(Duration.ofSeconds(2)))) {
            LineResponse response = session.request("hello");

            assertEquals("response:hello", response.text());
        }
    }

    @Test
    void readinessFailureClosesLineSessionBeforeReturn() {
        CommandExecutionException exception = assertThrows(CommandExecutionException.class, () -> fixtureService()
                .lineSession(call -> call.args("controlled-line-repl")
                        .readiness(ready -> {
                            throw new IllegalStateException("not ready");
                        })
                        .readinessTimeout(Duration.ofSeconds(2))));

        assertEquals(CommandExecutionException.Reason.READINESS_FAILED, exception.reason());
    }

    @Test
    void requestRejectsEmbeddedLineSeparators() {
        try (LineSession session = fixtureService().lineSession(call -> call.args("controlled-line-repl"))) {
            assertThrows(IllegalArgumentException.class, () -> session.request("a\nb"));
            assertThrows(IllegalArgumentException.class, () -> session.request("a\rb"));
        }
    }

    @Test
    void customDecoderCanReadMultipleLines() {
        CommandService service = fixtureService(LineSessionOptions.defaults()
                .withResponseDecoder(reader -> List.of(reader.readLine(), reader.readLine())));

        try (LineSession session = service.lineSession(call -> call.args("controlled-line-repl"))) {
            LineResponse response = session.request("multi");

            assertEquals(List.of("first:multi", "second:multi"), response.lines());
            assertEquals("first:multi\nsecond:multi", response.text());
        }
    }

    @Test
    void perRequestTimeoutIsDistinctAndPreservesTranscript() {
        ResponseDecoder waitForDone = reader -> {
            while (true) {
                String line = reader.readLine();
                if (line.equals("done")) {
                    return List.of(line);
                }
            }
        };
        CommandService service = fixtureService(LineSessionOptions.defaults().withResponseDecoder(waitForDone));

        try (LineSession session = service.lineSession(call -> call.args("controlled-line-repl"))) {
            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("slow", Duration.ofSeconds(1)));

            assertEquals(LineSessionException.Reason.TIMEOUT, exception.reason());
            assertTrue(exception.transcript().text().contains("stdout: started:slow"));
            LineSessionException closed =
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofSeconds(1)));
            assertEquals(LineSessionException.Reason.CLOSED, closed.reason());
        }
    }

    @Test
    void perRequestTimeoutBoundsStdinWrite() {
        try (LineSession session = fixtureService().lineSession(call -> call.args("ignore-stdin", "--millis=5000"))) {
            LineSessionException exception = assertThrows(
                    LineSessionException.class,
                    () -> session.request("x".repeat(8 * 1024 * 1024), Duration.ofMillis(100)));

            assertEquals(LineSessionException.Reason.TIMEOUT, exception.reason());
            LineSessionException closed =
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofSeconds(1)));
            assertEquals(LineSessionException.Reason.CLOSED, closed.reason());
        }
    }

    @Test
    void timeoutTranscriptIncludesPartialUnterminatedOutput() {
        try (LineSession session = fixtureService()
                .lineSession(
                        call -> call.args("partial", "--stdout=", "--stderr=partial-error", "--hold-millis=5000"))) {
            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofSeconds(1)));

            assertEquals(LineSessionException.Reason.TIMEOUT, exception.reason());
            assertTrue(exception.transcript().text().contains("stderr: partial-error"));
        }
    }

    @Test
    void transcriptAttributesInterleavedPartialOutputToStreams() {
        try (LineSession session = fixtureService()
                .lineSession(call ->
                        call.args("partial", "--stdout=partial-out", "--stderr=partial-err", "--hold-millis=5000"))) {
            LineSessionException exception = assertThrows(
                    LineSessionException.class, () -> session.request("hello", timeoutAfterFixtureStartup()));

            assertEquals(LineSessionException.Reason.TIMEOUT, exception.reason());
            assertTrue(exception.transcript().text().contains("stdout: partial-out"));
            assertTrue(exception.transcript().text().contains("stderr: partial-err"));
        }
    }

    @Test
    void eofBeforeResponseIsDistinct() {
        try (LineSession session = fixtureService().lineSession(call -> call.args("exit-after-read"))) {
            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofSeconds(1)));

            assertEquals(LineSessionException.Reason.EOF, exception.reason());
        }
    }

    @Test
    void decoderFailureIsDistinct() {
        CommandService service = fixtureService(LineSessionOptions.defaults().withResponseDecoder(reader -> {
            throw new IllegalArgumentException("bad response");
        }));

        try (LineSession session = service.lineSession(call -> call.args("controlled-line-repl"))) {
            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofSeconds(1)));

            assertEquals(LineSessionException.Reason.DECODER_FAILED, exception.reason());
        }
    }

    @Test
    void stdoutBacklogOverflowIsDistinctFailure() {
        ResponseDecoder delayedDecoder = reader -> {
            sleep(Duration.ofMillis(300));
            return List.of(reader.readLine());
        };
        CommandService service = fixtureService(
                LineSessionOptions.defaults().withStdoutBacklogLimit(1).withResponseDecoder(delayedDecoder));

        try (LineSession session = service.lineSession(call -> call.args("controlled-line-repl"))) {
            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("many", Duration.ofSeconds(2)));

            assertEquals(LineSessionException.Reason.STDOUT_BACKLOG_OVERFLOW, exception.reason());
        }
    }

    @Test
    void unterminatedStdoutLineIsBounded() {
        CommandService service = fixtureService(LineSessionOptions.defaults().withMaxLineChars(32));

        try (LineSession session = service.lineSession(
                call -> call.args("partial", "--stdout=" + "x".repeat(128), "--stderr=", "--hold-millis=5000"))) {
            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofSeconds(2)));

            assertEquals(LineSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
            assertTrue(exception.getCause().getMessage().contains("maxLineChars"));
        }
    }

    @Test
    void transcriptIsBounded() {
        CommandService service = fixtureService(
                LineSessionOptions.defaults().withTranscriptLimit(80).withResponseDecoder(reader -> {
                    String line;
                    do {
                        line = reader.readLine();
                    } while (!line.equals("done"));
                    return List.of(line);
                }));

        try (LineSession session = service.lineSession(call -> call.args("controlled-line-repl"))) {
            LineResponse response = session.request("many");

            assertEquals("done", response.text());
            assertTrue(response.transcript().truncated());
            assertTrue(response.transcript().text().contains("done"));
        }
    }

    @Test
    void stderrIsDrainedWhileWaitingForStdoutResponse() throws Exception {
        try (LineSession session = fixtureService().lineSession(call -> call.args("controlled-line-repl"))) {
            LineResponse response = session.request("stderr-burst");

            assertEquals("response:stderr-burst", response.text());
            assertTrue(eventuallyTranscriptTruncated(session));
        }
    }

    @Test
    void parallelRequestsAreSerialized() throws Exception {
        ResponseDecoder twoLineDecoder = reader -> List.of(reader.readLine(), reader.readLine());
        CommandService service = fixtureService(LineSessionOptions.defaults().withResponseDecoder(twoLineDecoder));

        try (LineSession session =
                service.lineSession(call -> call.args("two-line-delay-repl", "--delay-millis=100"))) {
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

    private static CommandService fixtureService() {
        return fixtureService(LineSessionOptions.defaults());
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

    private static void sleep(Duration duration) {
        try {
            TimeUnit.NANOSECONDS.sleep(duration.toNanos());
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("interrupted", exception);
        }
    }

    private static Duration timeoutAfterFixtureStartup() {
        return isWindows() ? Duration.ofSeconds(2) : Duration.ofSeconds(1);
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }

    private static CommandService fixtureService(LineSessionOptions lineSessionOptions) {
        return new CommandService(
                TestCliSupport.command(), RunOptions.defaults(), SessionOptions.defaults(), lineSessionOptions);
    }
}
