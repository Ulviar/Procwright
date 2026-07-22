# Policies and Draft settings

Configure policies on the scenario Draft that owns their meaning. Every `with*` method validates its value and returns a
new Draft; the original remains reusable.

## Persistent callback concurrency

Drafts and PoolDrafts are immutable, but configured callback and service-provider objects are retained by reference. If
one Draft is reused for concurrent terminal calls, or one PoolDraft creates concurrent workers or multiple pools, the
same supplied instance may be invoked concurrently. Make every shared instance thread-safe, or use separate Draft
branches with separate callback instances.

| Retained API surface | Invocation contract |
| --- | --- |
| `CommandService.protocolSession` | Factory calls may overlap across direct opens, worker startup, and separate pools. Every call must return a fresh adapter. One worker serializes its adapter calls; different adapters can run concurrently. |
| `RunScenario.Draft.withDiagnosticListener`, `RunScenario.Draft.withDiagnosticTranscriptSink` | Independent executions can deliver diagnostics concurrently. |
| `InteractiveScenario.Draft.withPtyProvider`, `InteractiveScenario.Draft.withReadiness`, `InteractiveScenario.Draft.withDiagnosticListener`, `InteractiveScenario.Draft.withDiagnosticTranscriptSink` | Each open runs readiness once and waits for it, but concurrent opens can overlap readiness, provider, and diagnostics calls. |
| `LineSessionScenario.Draft.withPtyProvider`, `LineSessionScenario.Draft.withReadiness`, `LineSessionScenario.Draft.withResponseDecoder`, `LineSessionScenario.Draft.withDiagnosticListener`, `LineSessionScenario.Draft.withDiagnosticTranscriptSink` | One line worker serializes requests and decoder calls. Direct sessions and line-pool workers can invoke the retained instances concurrently. |
| `LineSessionScenario.PoolDraft.withHealthCheck`, `LineSessionScenario.PoolDraft.withReset` | One line worker runs health, request, and reset work in order; different workers or pools can invoke the same hooks concurrently. |
| `ProtocolSessionScenario.Draft.withPtyProvider`, `ProtocolSessionScenario.Draft.withReadiness`, `ProtocolSessionScenario.Draft.withDiagnosticListener`, `ProtocolSessionScenario.Draft.withDiagnosticTranscriptSink` | Each worker has a fresh adapter and serializes its request cycle. Direct sessions and protocol-pool workers can invoke the other retained instances concurrently. |
| `ProtocolSessionScenario.PoolDraft.withHealthCheck`, `ProtocolSessionScenario.PoolDraft.withReset` | One protocol worker runs health, request, and reset work in order; different workers or pools can invoke the same hooks concurrently. |
| `StreamScenario.Draft.onOutput`, `StreamScenario.Draft.withDiagnosticListener`, `StreamScenario.Draft.withDiagnosticTranscriptSink` | One stream session invokes its output listener synchronously and serializes stdout/stderr chunks. Concurrent opens can call the same listener from different sessions. |

Readiness and pool hooks run on bounded callback executors while the open, acquire, or request operation waits for them.
A terminal policy can call a retained `PtyProvider` concurrently when terminal-enabled sessions or workers start in
parallel.

The readers and writers passed into line decoders and protocol adapters are not retained callback objects. They are
request capabilities: callback-scoped, confined to the callback thread, and invalid as soon as that invocation returns.
Do not store them in adapter state or hand them to another thread.

Diagnostic listener and transcript-sink delivery is asynchronous and best-effort. One lifecycle serializes calls to each
destination in submission order, but listener and sink delivery use independent queues. Separate executions, sessions,
and pool workers also use independent queues, so calls to a shared recipient can overlap.

## Launch settings

All scenarios can append argv, set a working directory, choose inherited or clean environment construction, and add
environment entries. Session-family scenarios can also select terminal policy and a `PtyProvider`.

## Timeouts and shutdown

- `run().withTimeout(...)` and `listen().withTimeout(...)` set absolute scenario deadlines; zero disables the deadline.
- `interactive`, line, and protocol `withIdleTimeout(...)` supervise inactivity.
- line and protocol `withRequestTimeout(...)` bound one request/response exchange.
- `Expect.Draft.withTimeout(...)` sets the default match deadline.
- pool `withAcquireTimeout(...)`, `withHookTimeout(...)`, and `withCloseTimeout(...)` separately bound worker acquisition,
  lifecycle hooks, and synchronous close-and-drain.
- `withShutdown(ShutdownPolicy)` configures graceful and forceful process-stop deadlines.

A pooled call has no single overall deadline. Acquisition uses `withAcquireTimeout(...)`; worker startup and health
selection occur inside that window, and a health check is capped by both the remaining acquire time and
`withHookTimeout(...)`. Request encoding and response decoding consume the request deadline. Line-request encoding starts
before acquisition but acquisition is not charged to that request deadline. After a successful response, reset has its
own `withHookTimeout(...)` budget. Caller latency can therefore compose request preparation/request time, acquisition,
and reset phases, depending on the path taken.

The default pool close timeout is exactly 15 seconds: 5 seconds for a normal request, the default 2-second interrupt grace
plus 5-second kill grace for worker shutdown, and a 3-second reserve for scheduling and stream cleanup. A close timeout
does not cancel a healthy in-flight request or internal cleanup.

Timeouts do not make arbitrary user callbacks interruptible. Procwright bounds abandoned callback capacity, but Java cannot
forcibly stop callback code that ignores interruption.

## Output and encoding

`CapturePolicy` controls one-shot retained output. Line, protocol, Expect, stream diagnostics, and pool failures each have
scenario-specific retained transcript limits.

`CharsetPolicy.report(charset)` rejects malformed or unmappable text. `CharsetPolicy.replace(charset)` substitutes invalid
sequences. Text transcripts record malformed, truncated, or redacted state where the corresponding scenario exposes it.

Line and protocol Drafts also bound request size, response size, and unread output backlog. These limits protect active
protocol work; transcript limits only bound retained diagnostics.

## Readiness and pooling

`withReadiness(probe)` runs after launch and before a session is returned or a pool worker becomes idle. Pair it with
`withReadinessTimeout(...)`. Failure closes the process.

PoolDraft settings control maximum size, warmup, minimum idle workers, worker age, requests per worker, background
replenishment, reset, and health checks. Worker settings are configured before `pooled()`; pool settings are configured
after it.

See the [scenario defaults](defaults.md) for exact initial values, each [scenario contract](../scenarios/index.md) for
behavior, and the [generated Java API](../api/index.md) for exact methods.
