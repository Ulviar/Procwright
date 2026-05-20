# Reuse Workers

Use `pooled` when a CLI worker has expensive startup cost and supports a safe line-oriented request/response protocol.
Use `pooledProtocol` for framed, multi-line, byte, or typed request/response protocols.

## Steps

1. Confirm the worker protocol is safely reusable.
2. Choose `pooled` for line workers or `pooledProtocol` for adapter-owned protocols.
3. Define maximum pool size, warmup, and optional minimum idle workers.
4. Add bounded reset and health hooks when the worker has mutable protocol state.
5. Set worker retirement policies such as request limit or age when useful.

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

Typed protocol pool shape is compile-tested as `CommandServiceApiExamples.pooledProtocolSessionScenario`. It uses a
per-worker adapter factory so adapter state is never shared between workers.

## Use this scenario because

The pool owns worker acquisition, reuse, retirement, and graceful close. The caller still owns protocol safety and reset
semantics.
