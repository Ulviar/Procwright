# Settings and lifecycle

Every `with*` method returns a new Draft; the original remains reusable. Configure settings before `execute()` or
`open()`. Invalid values and incompatible combinations are rejected before a process starts, either when you configure
the Draft or when you execute or open it.

For initial values, see [scenario defaults](defaults.md). For exact signatures, see the [Java API](../api/index.md).

## Launch settings

Use `withArgs(...)`, `withWorkingDirectory(...)`, and `withEnvironment(name, value)` on any scenario Draft.
`withInheritedEnvironment()` starts from the parent environment; `withCleanEnvironment()` starts from an empty one.
Both apply your configured environment entries afterward. Interactive, Expect, line, and protocol sessions can also
select a [terminal policy and provider](platforms-and-pty.md).

## Timeouts and shutdown

| Operation | Setting |
| --- | --- |
| Complete command or output stream | `run().withTimeout(...)` or `listen().withTimeout(...)`; zero disables the absolute deadline |
| Session inactivity | `withIdleTimeout(...)` on interactive, Expect, line, or protocol Drafts |
| One request/response exchange | `withRequestTimeout(...)` on line or protocol Drafts |
| One Expect match | `interactive().expect().withTimeout(...)` |
| Wait for a pool worker | `withAcquireTimeout(...)` |
| Pool health check or reset | `withHookTimeout(...)` |
| Wait for pool close-and-drain | `withCloseTimeout(...)` |
| Graceful and forceful process stop | `withShutdown(ShutdownPolicy)` |

A pooled call has no single overall deadline. Acquisition and reset have their own budgets, so a call can take longer
than its request timeout. See [pool timeout rules](../scenarios/pooling.md#timeouts-and-failures) for how startup, health
checks, and request preparation consume those budgets.

The default pool close timeout is 15 seconds. It bounds the caller's wait, not the lifetime of cleanup.
Use `closeAsync()` to observe completion after a close timeout; closing does not cancel a healthy in-flight request.

Request, readiness, and pool-hook timeouts bound the wait; they cannot forcibly stop Java code that ignores interruption.
Whether a timed-out session can be reused depends on the
[scenario and failure stage](results-and-errors.md#decide-whether-the-session-can-be-reused); independent handles remain
isolated. Streaming close and timeout reject new deliveries, but an already admitted callback can still begin or finish after closure. See
[stream completion rules](../scenarios/streaming.md#completion-and-shutdown).

## Output and encoding

`CapturePolicy` controls one-shot output: retain it in memory, redirect it to files, or discard it. See
[run output options](../scenarios/run.md). Line, protocol, Expect, stream diagnostics, and pool failures have their own
retained transcript limits.

`CharsetPolicy.report(charset)` rejects malformed or unmappable text. `CharsetPolicy.replace(charset)` substitutes invalid
sequences. Transcripts report malformed, truncated, or redacted state where the scenario exposes it.
Expect uses `withCharset(...)` for both input and output; `withOutputCharset(...)` overrides only output decoding when
the command uses different encodings in each direction.

Line and protocol Drafts bound request and response sizes. Unread output buffers follow the response limits;
transcript limits only bound retained diagnostics. The [output ownership reference](output-ownership.md) explains
which scenarios expose raw streams and which read them for you.

## Readiness and pooling

`withReadiness(probe)` runs once after launch and before a session is returned or a pool worker becomes available.
Pair it with `withReadinessTimeout(...)`. Failure closes the process.

Configure worker settings before `pooled()` and pool settings after it. PoolDraft settings control maximum size, warmup,
minimum idle workers, worker age, requests per worker, reset, and health checks. A positive minimum idle value enables
background replenishment; zero disables it. See the [worker reuse walkthrough](../how-to/reuse-workers.md) to configure a
pool for concurrent requests.

## Callback concurrency and lifetime

Drafts and PoolDrafts are immutable, but they retain supplied callbacks and providers by reference. Concurrent
executions, sessions, workers, or pools can call a shared instance at the same time. Make shared instances thread-safe,
or give separate Draft branches separate callback instances. Mutable state captured by a callback is also shared.

The scenario determines which calls are serialized:

| Callback | Invocation rule |
| --- | --- |
| [Line response decoder](../scenarios/line-session.md#concurrent-calls-and-callbacks) | Each session or worker serializes its requests and decoder calls. |
| [Protocol adapter](../scenarios/protocol-session.md#adapter-ownership) | The factory must return a fresh adapter for every session or worker and may be called concurrently. Each adapter's request cycles are serialized; writing finishes before response decoding starts. |
| [Pool health and reset hooks](../scenarios/pooling.md#reuse-and-callbacks) | Health, request, and reset work run in order for one worker. |
| [Streaming listener](../scenarios/streaming.md#output-delivery) | Each session serializes synchronous calls across stdout and stderr. A slow listener applies backpressure instead of buffering further callbacks. |

Readiness and pool hooks run on fresh task threads while the open, acquire, or request operation waits. A custom
`PtyProvider` can be called concurrently during parallel launches and must follow the
[provider timing contract](platforms-and-pty.md#custom-providers).

Readers and writers passed to line decoders and protocol adapters are valid only on the callback's thread and until it
returns. Do not store them or pass them to another thread.

[Diagnostic listeners and transcript sinks](diagnostics.md) receive asynchronous, best-effort delivery. Each destination
receives serial calls in submission order for one lifecycle. Listener and sink queues are independent, as are queues
for separate executions, sessions, and workers; calls to a shared recipient can overlap.

## Lifecycle futures

Cancelling a lifecycle `CompletableFuture` view does not cancel its session or pool. Non-async continuations may run on
Procwright's completion thread and must not block. Use an explicit executor with `thenApplyAsync`, `thenAcceptAsync`,
or `whenCompleteAsync` for blocking application work.
