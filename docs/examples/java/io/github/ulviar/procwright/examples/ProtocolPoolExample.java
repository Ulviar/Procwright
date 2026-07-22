/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.examples.DocumentProtocol.DocumentRequest;
import io.github.ulviar.procwright.examples.DocumentProtocol.DocumentResponse;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class ProtocolPoolExample {

    private ProtocolPoolExample() {}

    public static void main(String[] args) {
        try (PooledProtocolSession<DocumentRequest, DocumentResponse> pool = Procwright.command(
                        ExampleSupport.workerCommand("protocol"))
                .protocolSession(LengthLineFrameAdapter::new)
                .withReadiness(worker -> {
                    DocumentResponse response = worker.request(new DocumentRequest("readiness"), Duration.ofSeconds(2));
                    if (!response.text().equals("readiness")) {
                        throw new IllegalStateException("Protocol worker is not ready");
                    }
                })
                .withReadinessTimeout(Duration.ofSeconds(3))
                .withRequestTimeout(Duration.ofSeconds(5))
                .withTranscriptLimit(16 * 1024)
                .withOutputBacklogLimit(128 * 1024)
                .withMaxRequestBytes(64 * 1024)
                .withMaxRequestChars(64 * 1024)
                .withMaxResponseBytes(64 * 1024)
                .withMaxResponseChars(64 * 1024)
                .withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8))
                .pooled()
                .withMaxSize(2)
                .withWarmupSize(1)
                .withMinIdle(1)
                .open()) {
            DocumentResponse response = pool.request(new DocumentRequest("document\nданные ✓"), Duration.ofSeconds(5));
            if (!response.text().equals("document\nданные ✓")) {
                throw new IllegalStateException("Unexpected pooled protocol response");
            }
        }
    }
}
