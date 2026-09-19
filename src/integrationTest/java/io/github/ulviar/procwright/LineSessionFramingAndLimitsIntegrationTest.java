/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.LineSessionIntegrationFixtures.fixtureScenario;
import static io.github.ulviar.procwright.LineSessionIntegrationFixtures.openLineSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.LineSessionException;
import io.github.ulviar.procwright.session.ResponseDecoder;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

final class LineSessionFramingAndLimitsIntegrationTest {

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
    void lineOfExactlyMaxResponseCharsWithLineFeedTerminatorSucceeds() {
        LineSessionScenario.Draft service = fixtureScenario().withMaxResponseChars("response:hello".length());

        try (LineSession session = openLineSession(service, call -> call.withArgs("controlled-line-repl"))) {
            assertEquals("response:hello", session.request("hello").text());
        }
    }

    @Test
    void lineOfExactlyMaxResponseCharsWithCrLfTerminatorSucceeds() {
        LineSessionScenario.Draft service = fixtureScenario().withMaxResponseChars("response:hello".length());

        try (LineSession session =
                openLineSession(service, call -> call.withArgs("controlled-line-repl", "--crlf=true"))) {
            assertEquals("response:hello", session.request("hello").text());
        }
    }

    @Test
    void lineBeyondMaxResponseCharsIsTypedFailureForBothTerminators() {
        for (String[] args : new String[][] {{"controlled-line-repl"}, {"controlled-line-repl", "--crlf=true"}}) {
            LineSessionScenario.Draft service = fixtureScenario().withMaxResponseChars("response:hello".length() - 1);

            try (LineSession session = openLineSession(service, call -> call.withArgs(args))) {
                LineSessionException exception =
                        assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofSeconds(2)));

                assertEquals(LineSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
            }
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
        LineSessionScenario.Draft service = fixtureScenario().withMaxResponseChars(32);

        try (LineSession session = openLineSession(
                service,
                call -> call.withArgs("partial", "--stdout=" + "x".repeat(128), "--stderr=", "--hold-millis=5000"))) {
            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofSeconds(2)));

            assertEquals(LineSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
            assertTrue(exception.getCause().getMessage().contains("maxResponseChars"));
        }
    }

    @Test
    void loneCarriageReturnAtEofCannotExceedLineLimit() {
        LineSessionScenario.Draft service = fixtureScenario().withMaxResponseChars(3);

        try (LineSession session = openLineSession(
                service,
                call -> call.withArgs(
                        "binary", "--pattern=hex", "--hex=7878780d", "--stream=stdout", "--hold-millis=100"))) {
            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofSeconds(2)));

            assertEquals(LineSessionException.Reason.RESPONSE_TOO_LARGE, exception.reason());
            assertTrue(exception.getCause().getMessage().contains("maxResponseChars"));
        }
    }
}
