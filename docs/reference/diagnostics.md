# Diagnostics

Attach a listener or transcript sink directly to a scenario Draft before its terminal call.

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/DiagnosticsExample.java -->
```java
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
```

[Open `DiagnosticsExample.java`](../examples/java/io/github/ulviar/procwright/examples/DiagnosticsExample.java) and the
[shared example sources](../examples.md#core).

Diagnostic delivery is asynchronous and bounded. For one command or session lifecycle, calls to each listener or sink are
serialized in submission order. Listener and sink delivery use independent queues and may overlap. Separate executions,
sessions, and pool workers also use independent queues. Reusing a Draft therefore lets the same supplied recipient run
concurrently; make it thread-safe or use separate Draft branches with separate recipients.

A short-lived program that must observe a terminal event should wait for that event, as the example does. Diagnostics are
observation only; listener failure does not become process control logic.

Transcripts retain bounded event snapshots. Sensitive argv, environment values, request bodies, output, and exception
messages can reach diagnostics unless the application filters or redacts them before export. Do not treat a size limit as
redaction.
