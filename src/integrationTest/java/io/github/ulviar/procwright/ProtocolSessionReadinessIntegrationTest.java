/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.FramedStringAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.fixtureService;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationFixtures.openProtocolSession;
import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.ulviar.procwright.session.ProtocolSession;
import java.time.Duration;
import org.junit.jupiter.api.Test;

final class ProtocolSessionReadinessIntegrationTest {

    @Test
    void readinessProbeRunsBeforeProtocolSessionIsReturned() {
        try (ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(),
                new FramedStringAdapter(),
                call -> call.withArgs("length-line-frame")
                        .withReadiness(ready -> assertEquals("ready", ready.request("ready")))
                        .withReadinessTimeout(Duration.ofSeconds(2)))) {
            assertEquals("payload", session.request("payload"));
        }
    }
}
