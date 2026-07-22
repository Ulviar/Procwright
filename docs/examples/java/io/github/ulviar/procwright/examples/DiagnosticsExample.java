/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.diagnostics.DiagnosticEventType;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public final class DiagnosticsExample {

    private DiagnosticsExample() {}

    public static void main(String[] args) throws Exception {
        CountDownLatch processExited = new CountDownLatch(1);
        var run = Procwright.command(ExampleSupport.workerCommand("finite"))
                .run()
                .withDiagnosticListener(event -> {
                    if (event.type() == DiagnosticEventType.PROCESS_EXITED) {
                        processExited.countDown();
                    }
                });

        run.withTimeout(Duration.ofSeconds(5)).execute();
        if (!processExited.await(5, TimeUnit.SECONDS)) {
            throw new IllegalStateException("PROCESS_EXITED diagnostic was not delivered");
        }
    }
}
