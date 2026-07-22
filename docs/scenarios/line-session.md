# Line sessions

`lineSession()` models one request line followed by one stdout response line.

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/LineSessionExample.java -->
```java
/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSession;
import java.time.Duration;

public final class LineSessionExample {

    private LineSessionExample() {}

    public static void main(String[] args) {
        try (LineSession session = Procwright.command(ExampleSupport.workerCommand("line"))
                .lineSession()
                .withRequestTimeout(Duration.ofSeconds(5))
                .open()) {
            LineResponse response = session.request("Zażółć gęślą jaźń");
            if (!response.text().equals("response:Zażółć gęślą jaźń")) {
                throw new IllegalStateException("Unexpected line response");
            }
        }
    }
}
```

[Open `LineSessionExample.java`](../examples/java/io/github/ulviar/procwright/examples/LineSessionExample.java) and the
[shared example sources](../examples.md#core).

One session handles one request at a time. Request and response limits are global per exchange, while the retained
transcript has its own bound. `CharsetPolicy.report(...)` rejects malformed text; `replace(...)` substitutes malformed
input.

The Draft retains a custom response decoder. Decoder calls are serialized within one line session, but concurrent direct
opens and line-pool workers can invoke that same decoder instance concurrently. The same cross-worker rule applies to
readiness, diagnostics recipients, and a custom PTY provider. Make shared instances thread-safe or use separate Draft
branches with separate instances.

`ResponseDecoder.Reader` is callback-scoped and thread-confined. Use it only on the thread executing the decoder and do
not retain it after the decoder returns. A late or cross-thread read fails before consuming output, so it cannot steal a
line from a later request.

A local request-preparation or wait failure that completes before a request is handed off for stdin writing, when no
later write can occur, leaves the direct session open and can be retried. This includes line validation, request-size
checks, encoding, and deadlines while waiting for another request or for stdin writing to become available. `onExit()`
remains incomplete unless the worker exits independently.
Once the request is handed off, a timeout, interruption, or write failure closes the session even if no received byte can
be confirmed. EOF, malformed text, oversized output, backlog overflow, and other response/protocol failures are also
terminal. Use `protocolSession` when messages may contain embedded newlines or need custom framing.

Readiness runs after launch and before `open()` returns. A pooled worker also completes readiness before it becomes idle.
A failed or timed-out readiness probe closes the process.

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/ReadinessExample.java -->
```java
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
```

[Open `ReadinessExample.java`](../examples/java/io/github/ulviar/procwright/examples/ReadinessExample.java) and the
[shared example sources](../examples.md#core).

See [scenario defaults](../reference/defaults.md#line-sessions) for request, backlog, line, response, decoding, terminal,
and readiness limits.
