# Pooling

A pool handles independent concurrent requests with interchangeable line or protocol workers. It chooses a worker for
each call; there is no caller-to-worker affinity. If later requests depend on a particular process's state, keep a direct
session.

Start with the [concurrent worker walkthrough](../how-to/reuse-workers.md). Configure the worker Draft, call `pooled()`,
configure the pool, then `open()` it. Use try-with-resources to close the pool.

Runnable examples: [line pool](../examples/java/io/github/ulviar/procwright/examples/LinePoolExample.java),
[protocol pool with readiness](../examples/java/io/github/ulviar/procwright/examples/ProtocolPoolExample.java), and
[close-timeout handling](../examples/java/io/github/ulviar/procwright/examples/PoolDrainTimeoutExample.java).

## Capacity and startup

`withMaxSize(...)` accepts 1 through 256 workers and defaults to 1. Starting, idle, busy, and retiring workers all count
against this pool's maximum. Separate pools and direct sessions have no shared process quota, so size them together at
the application level.

Workers start on demand unless you configure startup or replenishment:

- `withWarmupSize(...)` makes `open()` wait for that many ready workers.
- `withMinIdle(...)` maintains ready idle workers in the background, without exceeding `maxSize`. Its default of zero
  disables replenishment; a positive value starts replenishment when the pool opens.
- Worker `withReadiness(...)` runs before a new worker becomes available, including during warmup and replenishment.

Startup or synchronous warmup failure reports `STARTUP_FAILED`. A request that cannot obtain a worker before its acquire
deadline reports `ACQUIRE_TIMEOUT`.

## Reuse and callbacks

One worker serves one request at a time. `withHealthCheck(...)` checks a candidate before use;
`withReset(...)` prepares it for reuse after a successful response. Set these hooks only when the worker protocol needs
them. `withMaxRequestsPerWorker(...)` and `withMaxWorkerAge(...)` can retire workers after enough requests or elapsed time.

For one worker, health, request, and reset callbacks do not overlap. Across workers and pools, callbacks may run
concurrently. A PoolDraft retains its hooks and its worker Draft's decoder, readiness, diagnostics recipients, and PTY
provider. Keep shared callbacks thread-safe or supply separate instances. Protocol pools call the adapter factory for
fresh per-worker adapters; mutable state captured outside that factory is still shared.

Hook timeouts bound the wait, not the execution of callback code that ignores interruption. A worker whose hook times
out is retired.

## Timeouts and failures

A pooled call has separate acquire, request, and hook budgets, not one overall deadline:

- Startup and health checks consume the acquire budget. A health check is also capped by the hook timeout.
- Request preparation, encoding, and response decoding consume the request budget; waiting for a worker does not.
- Reset after a successful response uses the hook timeout.

For line requests, validation and encoded-size checks happen before acquisition, and the encoded byte array is built
after it. Both preparation steps count toward the request budget.

| Failure | What the caller receives | What happens to the worker |
| --- | --- | --- |
| Acquisition or startup fails | `PooledSessionException` | Your request does not start. A late startup is closed. |
| Local line preparation fails | `IllegalArgumentException` for line separators; `LineSessionException` for size, timeout, or interruption; `PooledSessionException` for an unexpected runtime failure | No request is sent. An acquired worker can be reused; reset and its request counter are untouched. |
| Worker request fails | The original `LineSessionException` or `ProtocolSessionException` | The worker retires, including a pre-write failure that could leave a direct line session open. |
| Health returns false | Acquisition tries another worker within its deadline | The candidate retires. A thrown failure or timeout instead surfaces as a pooled exception. |
| Reset fails after a response | The completed response remains the result for a runtime failure or timeout; an `Error` is rethrown | The worker retires. |

After a request reaches a worker, failure does not prove that no input or side effect occurred. Do not blindly retry
non-idempotent work; use the worker protocol's acknowledgement or an application idempotency key.

`metrics()` reports worker counts, request and startup counts, and retirement reasons. For exception reason codes and
exit-code availability, see [results and errors](../reference/results-and-errors.md#pools).

## Close a pool

`close()` rejects new requests, lets healthy active requests finish, and closes all workers. It waits at most 15 seconds
by default; change that with `withCloseTimeout(...)`.

A close timeout throws `PooledSessionException` with reason `DRAIN_TIMEOUT`, but cleanup continues. Use `closeAsync()`
to start cleanup without waiting, or to observe its eventual completion after a timeout. Each returned future is an
independent view: cancelling it cannot cancel cleanup. Cleanup failure reports `WORKER_FAILED`; interruption of a
synchronous close reports `INTERRUPTED` and restores the interrupt flag.

Completion means pool slots are released and worker process outcomes and output processing have settled. It does not
wait for a potentially blocked physical stream close or a callback that has already timed out but ignores interruption.
A factory that returns a worker after closure has completed is handled separately: that worker is closed without changing
the completed future.

The [close-timeout example](../examples/java/io/github/ulviar/procwright/examples/PoolDrainTimeoutExample.java) declares
its pool before `try (pool)` and observes `closeAsync()` in `finally`. This also covers a close failure suppressed by an
earlier request exception.

See [pool defaults](../reference/defaults.md#line-and-protocol-pools) for all initial limits and timeouts.
