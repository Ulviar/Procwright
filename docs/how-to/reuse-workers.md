# Reuse initialized workers

Pool only workers whose protocol supports independent requests and whose state can be reset or health-checked.

## Line worker

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/LinePoolExample.java -->
```java
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
```

[Open `LinePoolExample.java`](../examples/java/io/github/ulviar/procwright/examples/LinePoolExample.java) and the
[shared example sources](../examples.md#core).

## Framed or typed worker

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/ProtocolPoolExample.java -->
```java
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
```

[Open `ProtocolPoolExample.java`](../examples/java/io/github/ulviar/procwright/examples/ProtocolPoolExample.java) and
the [shared example sources](../examples.md#core).

Configure worker behavior on the session Draft, call `pooled()`, then configure pool size and lifecycle on the returned
PoolDraft. `open()` performs configured warmup. A protocol adapter factory must create a fresh adapter for every worker.

The pool keeps leases internal. Concurrent callers may use different workers, so do not rely on caller affinity.
Worker request timeout, EOF or process exit, response or backlog overflow, write, decode, and protocol failures are
thrown directly as `LineSessionException` or `ProtocolSessionException`. Pooled exceptions instead report acquisition,
startup, surfaced hook or lifecycle failures, and close.
`close()` is a bounded synchronous close-and-drain, so normal Java code uses try-with-resources and Kotlin uses `use`.
The default close timeout is 15 seconds and `withCloseTimeout(...)` accepts any positive duration. Java resource scopes
automatically suppress a close failure when the body already failed.

Use `closeAsync()` only for a nonblocking terminal. It starts the same idempotent cleanup and returns a
cancellation-isolated future. `DRAIN_TIMEOUT` does not cancel that cleanup; another `closeAsync()` view can observe its
eventual completion. Healthy in-flight requests are allowed to finish, while a callback that ignores interruption can keep
the future incomplete even after bounded `close()` returns.

## Observe cleanup after a close timeout

Keep the pool handle outside the resource declaration and register the `closeAsync()` observer in `finally`. This also
covers a request failure whose `close()` failure is suppressed: Java preserves the request exception as primary, while
the observer still follows eventual cleanup. `closeAsync()` observes the same idempotent close operation; it does not
start a second worker cleanup. Do not block on the returned future because completion can depend on an in-flight callback
returning.

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/PoolDrainTimeoutExample.java -->
```java
/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.LineSessionScenario;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.PooledLineSession;

public final class PoolDrainTimeoutExample {

    private PoolDrainTimeoutExample() {}

    public static LineResponse requestAndClose(LineSessionScenario.PoolDraft draft, String request) {
        PooledLineSession pool = draft.open();
        try (pool) {
            return pool.request(request);
        } finally {
            pool.closeAsync().whenComplete((ignored, cleanupFailure) -> {
                if (cleanupFailure != null) {
                    cleanupFailure.printStackTrace(System.err);
                }
            });
        }
    }
}
```

[Open `PoolDrainTimeoutExample.java`](../examples/java/io/github/ulviar/procwright/examples/PoolDrainTimeoutExample.java).
