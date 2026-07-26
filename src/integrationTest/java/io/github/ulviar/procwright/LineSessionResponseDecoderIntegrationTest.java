/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.LineSessionException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class LineSessionResponseDecoderIntegrationTest extends LineSessionIntegrationSupport {

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
    void eofBeforeResponseIsDistinct() {
        try (LineSession session = openLineSession(fixtureScenario(), call -> call.withArgs("exit-after-read"))) {
            LineSessionException exception =
                    assertThrows(LineSessionException.class, () -> session.request("hello", Duration.ofSeconds(1)));

            assertEquals(LineSessionException.Reason.EOF, exception.reason());
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
            session.onExit().get(2, TimeUnit.SECONDS);
            LineSessionException followUp =
                    assertThrows(LineSessionException.class, () -> session.request("again", Duration.ofSeconds(1)));
            assertEquals(LineSessionException.Reason.DECODER_FAILED, followUp.reason());
        }
    }
}
