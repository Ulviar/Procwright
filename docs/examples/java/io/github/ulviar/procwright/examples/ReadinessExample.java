/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.session.LineSession;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

public final class ReadinessExample {

    private ReadinessExample() {}

    public static void main(String[] args) {
        AtomicBoolean readinessCompleted = new AtomicBoolean();
        try (LineSession session = Procwright.command(ExampleSupport.workerCommand("line"))
                .lineSession()
                .withReadiness(ready -> {
                    String response = ready.request("health").text();
                    if (!response.equals("response:health")) {
                        throw new IllegalStateException("Worker readiness check failed");
                    }
                    readinessCompleted.set(true);
                })
                .withReadinessTimeout(Duration.ofSeconds(5))
                .withRequestTimeout(Duration.ofSeconds(5))
                .open()) {
            if (!readinessCompleted.get()) {
                throw new IllegalStateException("Session opened before readiness completed");
            }
            if (!session.request("work").text().equals("response:work")) {
                throw new IllegalStateException("Worker failed after readiness completed");
            }
        }
    }
}
