/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.LineSessionIntegrationFixtures.fixtureScenario;
import static io.github.ulviar.procwright.LineSessionIntegrationFixtures.openLineSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.LineSessionException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class LineSessionResponseDecoderIntegrationTest {

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
    void eofBeforeResponseIsDistinct() throws Exception {
        try (LineSession session = openLineSession(fixtureScenario(), call -> call.withArgs("exit-after-read"))) {
            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofSeconds(1)));

            assertEquals(LineSessionException.Reason.EOF, exception.reason());
            assertEquals(0, session.onExit().get(2, TimeUnit.SECONDS).exitCode().orElseThrow());
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
            assertExitFailedWith(session, exception);
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
            ExecutionException exitFailure = assertThrows(
                    ExecutionException.class, () -> session.onExit().get(2, TimeUnit.SECONDS));
            assertSame(decoderError, exitFailure.getCause());
            AssertionError followUp =
                    assertThrows(AssertionError.class, () -> session.request("again", Duration.ofSeconds(1)));
            assertSame(decoderError, followUp);
        }
    }

    private static void assertExitFailedWith(LineSession session, LineSessionException selectedFailureSource) {
        ExecutionException observed =
                assertThrows(ExecutionException.class, () -> session.onExit().get(2, TimeUnit.SECONDS));
        assertSame(selectedFailureSource, observed.getCause());
    }
}
