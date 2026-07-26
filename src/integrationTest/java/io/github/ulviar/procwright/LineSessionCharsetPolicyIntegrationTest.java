/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.LineSessionException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class LineSessionCharsetPolicyIntegrationTest extends LineSessionIntegrationSupport {

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
}
