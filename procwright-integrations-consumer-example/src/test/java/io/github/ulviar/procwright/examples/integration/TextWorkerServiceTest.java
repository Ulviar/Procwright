/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.github.ulviar.procwright.session.ProtocolSessionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

final class TextWorkerServiceTest {

    @Test
    void serviceHandlesMultipleExchangesAndClosesWithItsOwner() {
        var service = new TextWorkerService(JsonLinesTextWorker.command());
        try (service) {
            assertEquals(new TextWorkerService.Metrics(5, 5), service.analyze("hello"));
            assertEquals(new TextWorkerService.Metrics(4, 5), service.analyze("café"));
            assertEquals(new TextWorkerService.Metrics(1, 4), service.analyze("🚀"));
        }
        assertThrows(ProtocolSessionException.class, () -> service.analyze("after close"));
    }

    @Test
    void theSameProtocolConfigurationSupportsConcurrentIndependentRequests() throws Exception {
        try (var pool = TextWorkerService.draft(JsonLinesTextWorker.command())
                        .pooled()
                        .withMaxSize(2)
                        .open();
                var requests = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = requests.submit(() -> pool.request(new TextWorkerService.Request("hello")));
            var second = requests.submit(() -> pool.request(new TextWorkerService.Request("café")));
            assertEquals(new TextWorkerService.Metrics(5, 5), first.get(15, TimeUnit.SECONDS));
            assertEquals(new TextWorkerService.Metrics(4, 5), second.get(15, TimeUnit.SECONDS));
        }
    }
}
