/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.RunOptions;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.LineSessionOptions;
import io.github.ulviar.procwright.session.ResponseDecoder;
import io.github.ulviar.procwright.session.SessionOptions;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
            LineSessionException followUp =
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofSeconds(1)));
            assertEquals(LineSessionException.Reason.TIMEOUT, followUp.reason());
            assertTrue(followUp.getMessage().contains("closed by an earlier failure"));
        }
    }

    @Test
    void perRequestTimeoutBoundsStdinWrite() {
        try (LineSession session = fixtureService().lineSession(call -> call.args("ignore-stdin", "--millis=5000"))) {
            LineSessionException exception = assertThrows(
                    LineSessionException.class,
                    () -> session.request("x".repeat(8 * 1024 * 1024), Duration.ofMillis(100)));

            assertEquals(LineSessionException.Reason.TIMEOUT, exception.reason());
            LineSessionException followUp =
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofSeconds(1)));
            assertEquals(LineSessionException.Reason.TIMEOUT, followUp.reason());
            assertTrue(followUp.getMessage().contains("closed by an earlier failure"));
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
                LineSessionOptions.defaults().withStdoutBacklogLines(1).withResponseDecoder(delayedDecoder));

        try (LineSession session = service.lineSession(call -> call.args("controlled-line-repl"))) {
            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("many", Duration.ofSeconds(2)));

            assertEquals(LineSessionException.Reason.STDOUT_BACKLOG_OVERFLOW, exception.reason());
        }
    }

    @Test
    void lineOfExactlyMaxLineCharsWithLineFeedTerminatorSucceeds() {
        CommandService service =
                fixtureService(LineSessionOptions.defaults().withMaxLineChars("response:hello".length()));

        try (LineSession session = service.lineSession(call -> call.args("controlled-line-repl"))) {
            assertEquals("response:hello", session.request("hello").text());
        }
    }

    @Test
    void lineOfExactlyMaxLineCharsWithCrLfTerminatorSucceeds() {
        CommandService service =
                fixtureService(LineSessionOptions.defaults().withMaxLineChars("response:hello".length()));

        try (LineSession session = service.lineSession(call -> call.args("controlled-line-repl", "--crlf=true"))) {
            assertEquals("response:hello", session.request("hello").text());
        }
    }

    @Test
    void lineBeyondMaxLineCharsIsTypedFailureForBothTerminators() {
        for (String[] args : new String[][] {{"controlled-line-repl"}, {"controlled-line-repl", "--crlf=true"}}) {
            CommandService service =
                    fixtureService(LineSessionOptions.defaults().withMaxLineChars("response:hello".length() - 1));

            try (LineSession session = service.lineSession(call -> call.args(args))) {
                LineSessionException exception =
                        assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofSeconds(2)));

                assertEquals(LineSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
            }
        }
    }

    @Test
    void strictCharsetPolicyReportsDecodeErrorAsTypedFailure() {
        CommandService service = fixtureService(
                LineSessionOptions.defaults().withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8)));

        try (LineSession session = service.lineSession(call -> call.args("controlled-line-repl"))) {
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
    void multiByteCodepointSplitAcrossChunksDecodesCorrectly() {
        CommandService service = fixtureService(
                LineSessionOptions.defaults().withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8)));

        try (LineSession session = service.lineSession(call -> call.args("controlled-line-repl"))) {
            assertEquals(
                    "П", session.request("split-utf8", Duration.ofSeconds(2)).text());
        }
    }

    @Test
    void crlfTerminatedResponsesDecodeWithoutTerminators() {
        try (LineSession session =
                fixtureService().lineSession(call -> call.args("controlled-line-repl", "--crlf=true"))) {
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
        CommandService service = fixtureService(LineSessionOptions.defaults().withResponseDecoder(decoder));

        try (LineSession session = service.lineSession(call -> call.args("line-repl", "--crlf=true"))) {
            assertEquals(
                    List.of("multi:0", "multi:1"), session.request(":multi 2").lines());
            assertEquals(List.of("response:hello"), session.request("hello").lines());
        }
    }

    @Test
    void carriageReturnWithoutLineFeedIsContentNotTerminator() {
        if (isWindows()) {
            // Embedded \r in an argv element is not portable through the Windows command line.
            return;
        }
        try (LineSession session = fixtureService()
                .lineSession(
                        call -> call.args("partial", "--stdout=alpha\rbeta\n", "--stderr=", "--hold-millis=5000"))) {
            assertEquals(
                    "alpha\rbeta",
                    session.request("ignored", Duration.ofSeconds(2)).text());
        }
    }

    @Test
    void callerInterruptDuringRequestIsTypedFailureAndRestoresInterruptStatus() throws Exception {
        try (LineSession session = fixtureService()
                .lineSession(call -> call.args("controlled-line-repl", "--slow-response-millis=60000"))) {
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
            caller.interrupt();
            caller.join(TimeUnit.SECONDS.toMillis(10));

            assertFalse(caller.isAlive(), "interrupted caller must not stay blocked in request()");
            assertTrue(
                    thrown.get() instanceof LineSessionException,
                    () -> "expected typed line-session failure, got " + thrown.get());
            assertEquals(LineSessionException.Reason.FAILURE, ((LineSessionException) thrown.get()).reason());
            assertTrue(interruptedAfterCatch.get(), "caller interrupt status must be restored after the typed failure");
        }
    }

    @Test
    void requestAfterStdoutBacklogOverflowReportsOverflowReason() {
        ResponseDecoder delayedDecoder = reader -> {
            sleep(Duration.ofMillis(300));
            return List.of(reader.readLine());
        };
        CommandService service = fixtureService(
                LineSessionOptions.defaults().withStdoutBacklogLines(1).withResponseDecoder(delayedDecoder));

        try (LineSession session = service.lineSession(call -> call.args("controlled-line-repl"))) {
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
        try (LineSession session = fixtureService().lineSession(call -> call.args("exit", "--stdout=gone\n"))) {
            session.onExit().get(2, TimeUnit.SECONDS);

            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofSeconds(1)));

            assertEquals(LineSessionException.Reason.PROCESS_EXITED, exception.reason());
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
