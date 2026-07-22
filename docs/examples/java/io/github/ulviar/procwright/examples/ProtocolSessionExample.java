/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.examples.DocumentProtocol.DocumentRequest;
import io.github.ulviar.procwright.examples.DocumentProtocol.DocumentResponse;
import io.github.ulviar.procwright.session.ProtocolSession;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public final class ProtocolSessionExample {

    private ProtocolSessionExample() {}

    public static void main(String[] args) {
        try (ProtocolSession<DocumentRequest, DocumentResponse> session = Procwright.command(
                        ExampleSupport.workerCommand("protocol"))
                .protocolSession(LengthLineFrameAdapter::new)
                .withRequestTimeout(Duration.ofSeconds(5))
                .withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8))
                .withMaxRequestBytes(16_384)
                .withMaxRequestChars(8192)
                .withMaxResponseBytes(16_384)
                .withMaxResponseChars(8192)
                .withOutputBacklogLimit(16_384)
                .open()) {
            DocumentResponse response = session.request(new DocumentRequest("first line\nПривет, 世界"));
            if (!response.text().equals("first line\nПривет, 世界")) {
                throw new IllegalStateException("Unexpected protocol response");
            }
        }
    }
}
