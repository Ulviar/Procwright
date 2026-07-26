/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright;

import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.FramedStringAdapter;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.fixtureService;
import static io.github.ulviar.procwright.ProtocolSessionIntegrationSupport.openProtocolSession;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolSession;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;

final class ProtocolSessionOpeningIntegrationTest {

    @Test
    void readinessProbeRunsBeforeProtocolSessionIsReturned() {
        try (ProtocolSession<String, String> session = openProtocolSession(
                fixtureService(), new FramedStringAdapter(), call -> call.withArgs("length-line-frame")
                        .withReadiness(ready -> assertEquals("ready", ready.request("ready")))
                        .withReadinessTimeout(Duration.ofSeconds(2)))) {
            assertEquals("payload", session.request("payload"));
        }
    }

    @Test
    void reusableProtocolScenarioOpensOneConfiguredWorker() {
        AtomicBoolean adapterCreated = new AtomicBoolean();
        Supplier<ProtocolAdapter<String, String>> adapterFactory = () -> {
            assertTrue(adapterCreated.compareAndSet(false, true));
            return new FramedStringAdapter();
        };

        try (ProtocolSession<String, String> session = fixtureService()
                .protocolSession(adapterFactory)
                .withArgs("length-line-frame")
                .withReadiness(ready -> assertEquals("ready", ready.request("ready")))
                .open()) {
            assertEquals("hello", session.request("hello"));
            assertTrue(adapterCreated.get());
        }
    }
}
