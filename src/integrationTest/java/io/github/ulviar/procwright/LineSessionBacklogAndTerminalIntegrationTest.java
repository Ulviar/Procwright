/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.ResponseDecoder;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class LineSessionBacklogAndTerminalIntegrationTest extends LineSessionBacklogIntegrationSupport {

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
}
