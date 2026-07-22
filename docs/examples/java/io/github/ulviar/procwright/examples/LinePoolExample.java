/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.PooledLineSession;
import java.time.Duration;

public final class LinePoolExample {

    private LinePoolExample() {}

    public static void main(String[] args) {
        try (PooledLineSession pool = Procwright.command(ExampleSupport.workerCommand("line"))
                .lineSession()
                .withRequestTimeout(Duration.ofSeconds(5))
                .withMaxRequestBytes(16 * 1024)
                .withMaxRequestChars(8 * 1024)
                .withMaxLineChars(8 * 1024)
                .withMaxResponseLines(1)
                .withMaxResponseChars(8 * 1024)
                .withStdoutBacklogLines(128)
                .withStdoutBacklogChars(64 * 1024)
                .pooled()
                .withMaxSize(2)
                .withWarmupSize(1)
                .withAcquireTimeout(Duration.ofSeconds(2))
                .withHookTimeout(Duration.ofSeconds(1))
                .withCloseTimeout(Duration.ofSeconds(15))
                .withMaxRequestsPerWorker(100)
                .open()) {
            LineResponse response = pool.request("Привет", Duration.ofSeconds(5));
            if (!response.text().equals("response:Привет")) {
                throw new IllegalStateException("Unexpected pooled response");
            }
        }
    }
}
