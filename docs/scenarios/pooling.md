# Pooling

Use `pooled` when a line-oriented worker is expensive to start and the protocol can be safely reused between requests.
Use `pooledProtocol` for framed, multi-line, binary, or typed workers.

The scenario covers:

- reuse of existing `LineSession` workers;
- maximum pool size;
- warmup size;
- acquire timeout;
- bounded reset and health hooks;
- worker retirement after failure or request limit;
- optional age retirement, minimum idle replenishment, and startup/request/acquire timing metrics;
- graceful drain;
- metrics snapshots.

Compile-tested source: `CommandServiceApiExamples.pooledLineSessionScenario`.

For typed protocol workers, see [Protocol Sessions](protocol-session.md).

## Example

```java
try (PooledLineSession pool = tool.pooled(call -> call.args("repl")
        .maxSize(4)
        .warmupSize(1)
        .maxRequestsPerWorker(100)
        .reset(worker -> worker.request("reset")))) {
    LineResponse response = pool.request("status", Duration.ofSeconds(2));
    PooledLineSessionMetrics metrics = pool.metrics();
    if (response.text().isBlank() || metrics.size() > 4) {
        throw new IllegalStateException("unexpected pooled response");
    }
}
```

Compile-tested source: `CommandServiceApiExamples.pooledLineSessionScenario`.

## User responsibilities

The caller owns protocol safety. A worker should be pooled only when reset and health semantics are clear. If a request
timeout, decoder failure, failed reset, failed health check, request limit, or worker age limit makes reuse unsafe, iCLI
retires the worker.

`pooledProtocol` uses an adapter factory, not a shared adapter instance. iCLI serializes factory calls; each worker owns
one returned adapter and one process protocol state.

Pooling is intentionally scenario-specific. iCLI has line-session and typed protocol pools, but raw session pooling,
stateful affinity, and exposed leases are not part of the current surface.
