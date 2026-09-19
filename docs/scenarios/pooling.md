# Pooling

Pooling reuses line or factory-backed protocol workers. Configure the worker Draft first, call `pooled()`, configure the
PoolDraft, then call `open()`.

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

[Open `LinePoolExample.java`](../examples/java/io/github/ulviar/procwright/examples/LinePoolExample.java),
[open `ProtocolPoolExample.java`](../examples/java/io/github/ulviar/procwright/examples/ProtocolPoolExample.java), and
the [shared example sources](../examples.md#core).

The line example permits at most 8,192 request characters and 16 KiB after UTF-8 encoding and line termination. It
accepts one response line of at most 8,192 characters and bounds unread stdout at 128 lines and 65,536 characters.
Its request, acquire, hook, and close timeouts are 5, 2, 1, and 15 seconds respectively.

The pool owns acquisition, release, and retirement; leases are not public. One worker serves one request at a time.
Concurrent callers may receive different workers, and caller affinity is not guaranteed.

`withMaxSize(...)` accepts 1 through 256 workers and limits one pool only; the default is 1. Starting, idle, leased, and
retiring workers all occupy that pool's slots. Separate line pools, protocol pools, and directly opened sessions do not
share a worker quota. Bound the application's aggregate process count by limiting how many pools and direct sessions it
creates and by choosing each pool's `maxSize`.

Each pool's `maxSize` bounds its concurrent worker startups; independent pools do not share startup admission. The hook
timeout applies only to hooks you supply. It bounds how long the caller waits, not how long non-cooperative callback
code can continue. A worker with a timed
out hook is not reused. Retirement processing uses a fixed owner set and a bounded queue; when that queue is full, the
caller performs the mandatory retirement step instead.

`STARTUP_FAILED` reports a worker startup or synchronous warmup failure. `ACQUIRE_TIMEOUT` reports that a request could
not obtain a worker from its own pool before the acquire deadline.

Protocol pool startup may invoke its adapter factory concurrently. The factory must be thread-safe and return a fresh
adapter for every worker. Keep mutable per-adapter state inside the factory call; externally captured mutable state remains
shared and must be synchronized or avoided.

Both line and protocol PoolDrafts retain their worker Draft callbacks and their reset and health hooks. Warmup and
replenishment run readiness before a worker becomes idle. For one worker, health, request, and reset work does not overlap.
Different workers, or separate pools opened from one PoolDraft, can invoke retained instances concurrently. For a line
pool, that includes readiness, the response decoder, diagnostics recipients, the PTY provider, health, and reset. For a
protocol pool, it includes the adapter factory, readiness, diagnostics recipients, the PTY provider, health, and reset.
Make shared instances thread-safe or build separate Draft or PoolDraft branches with separate callbacks.

`withMinIdle(0)` disables background replenishment. A positive value starts establishing that many ready idle workers
asynchronously when the pool opens, then restores the floor after workers are acquired or retired, without exceeding
`maxSize`. Use `withWarmupSize(...)` instead when `open()` must wait for workers to be ready.

Acquisition and worker request processing have separate deadlines. Worker startup and health selection consume the
acquire budget; a health callback is capped by the lesser of the remaining acquire budget and the hook timeout. Request
encoding and response decoding consume the request budget. A line pool validates the input and its encoded size before
acquire, then creates the encoded byte array when a worker becomes available. Both preparation phases consume the
request budget; the acquire wait does not. A configured reset after a successful response uses the separate hook
timeout. Observed caller latency may therefore compose request preparation/request, acquire, and reset phases rather
than stopping at one overall deadline.

| Phase | Caller outcome | Worker outcome |
| --- | --- | --- |
| Acquire fails before lease | A pooled exception reports `ACQUIRE_TIMEOUT`, `INTERRUPTED`, `STARTUP_FAILED`, or `CLOSED`. | No request worker was handed off; a late startup can still retire as `STARTUP_TIMEOUT` or `STARTUP_INTERRUPTED`. |
| Local line preparation fails | Invalid line separators produce `IllegalArgumentException`; size-limit, timeout, or interruption failures produce `LineSessionException`. An unexpected runtime failure produces `PooledSessionException`. | No request is sent. An acquired worker remains reusable, subject to normal age and close rules; reset and its request counter are untouched. |
| Worker request fails | `LineSessionException` or `ProtocolSessionException` is thrown directly for timeout, EOF or process exit, response or backlog overflow, write, decode, and protocol failures. It is not wrapped in a pooled exception. | The leased worker retires, commonly as `TIMEOUT`, `DECODER_FAILED`, `PROCESS_EXITED`, or `WORKER_FAILED`. |
| Health fails during acquire | A false result retires the candidate and acquire continues; timeout, interruption, or callback failure surfaces as a pooled exception. | The candidate retires as `HEALTH_FAILED`. |
| Reset fails after a successful response | A runtime failure, including reset timeout, does not replace the completed response; an `Error` is rethrown. | The worker retires as `RESET_FAILED`. |
| `close()` times out or is interrupted | The pooled exception reports `DRAIN_TIMEOUT` or `INTERRUPTED`; interruption restores the thread flag. | Cleanup continues and remains observable through `closeAsync()`. |

`PooledSessionException` reports acquisition, startup, surfaced hook or lifecycle failures, close, and unexpected local
preparation failures. Worker
loss can report `EOF` when output closure is selected first or `PROCESS_EXITED`
when process exit is selected first. For `ProtocolSessionException`, `exitCode()` is optional: `EOF` has no code, and
`PROCESS_EXITED` carries one only when it was available at failure selection.

After a worker is leased, failure does not prove that no bytes were written or no side effect occurred. Do not blindly
retry handed-off, non-idempotent work; use an application idempotency key or protocol-level acknowledgement.

`close()` is a bounded synchronous close-and-drain, so use try-with-resources or Kotlin `use` on the normal path. It
atomically rejects new requests, allows a healthy in-flight request to finish, closes every worker, and either reaches
logical drain or throws a typed cleanup failure. The default close timeout is exactly 15 seconds: the 5-second normal
request budget plus the default 2-second interrupt grace and 5-second kill grace, with a 3-second lifecycle-scheduling
and logical-output-settlement reserve. Override it with `withCloseTimeout(...)`.

A close timeout reports `DRAIN_TIMEOUT` without cancelling internal cleanup. Call `closeAsync()` when cleanup must start
without waiting for worker drain, or after a timeout to observe eventual logical completion. Logical drain means that no
pool slots remain, worker close has been invoked, and every worker's process outcome and output-mode processing have
settled. It does not wait for a potentially blocking physical close of process streams, and a later physical-close
failure cannot change the result. Each call returns a cancellation-isolated future view; cancelling or completing that
view cannot mutate cleanup. Completion actions never run while internal pool state is locked. Worker-close failure
reports `WORKER_FAILED`, and caller interruption reports `INTERRUPTED` after restoring the interrupt flag.

The [close-timeout handling example](../examples/java/io/github/ulviar/procwright/examples/PoolDrainTimeoutExample.java) declares the pool
before `try (pool)` and registers a `closeAsync()` observer in `finally`. It therefore observes cleanup even when a
request exception remains primary and `DRAIN_TIMEOUT` is suppressed by try-with-resources.

Java cannot forcibly stop a callback that ignores interruption. `close()` still returns at its configured timeout.
After the callback's operation deadline, logical abandonment can let pool drain complete while the callback thread
continues running; `closeAsync()` does not observe that thread's physical return. A close invoked by an active request
callback has the same bounded behavior and cannot permanently deadlock the pool.

See [scenario defaults](../reference/defaults.md#line-and-protocol-pools) for capacity, warmup, replenishment, hook,
retirement, acquisition, and close values.
