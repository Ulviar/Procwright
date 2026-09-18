/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.LineSessionIntegrationFixtures.fixtureScenario;
import static io.github.ulviar.procwright.LineSessionIntegrationFixtures.openLineSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.LineSessionException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

final class LineSessionReadinessAndAdmissionIntegrationTest {

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
        try (LineSession session = openLineSession(
                fixtureScenario(),
                call -> call.withArgs("controlled-line-repl")
                        .withReadiness(ready -> assertEquals(
                                "response:healthy", ready.request("health").text()))
                        .withReadinessTimeout(Duration.ofSeconds(2)))) {
            LineResponse response = session.request("hello");

            assertEquals("response:hello", response.text());
        }
    }

    @Test
    void readinessFailureClosesLineSessionBeforeReturn() {
        CommandExecutionException exception = assertThrows(
                CommandExecutionException.class,
                () -> openLineSession(
                        fixtureScenario(),
                        call -> call.withArgs("controlled-line-repl")
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
}
