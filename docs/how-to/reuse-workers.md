# Reuse Workers

Use `lineSession().pooled()` when a CLI worker has expensive startup cost and supports a reliable line-oriented
request/response protocol. Use `protocolSession(factory).pooled()` for framed, multi-line, byte, or typed protocols.

## Steps

1. Confirm reuse conditions: each request has a deterministic end, reset clears mutable state, a health command exists
   when the worker can degrade, and timeout behavior is understood.
2. Choose `lineSession().pooled()` for line workers or `protocolSession(factory).pooled()` for adapter-owned protocols.
3. Define maximum pool size, warmup, and optional minimum idle workers.
4. Add bounded reset and health hooks when the worker has mutable protocol state.
5. Set worker retirement policies such as request limit or age when useful.

```java
CommandService tool = Icli.command("tool");

try (PooledLineSession pool = tool.lineSession()
        .withArgs("repl")
        .pooled()
        .withMaxSize(4)
        .withWarmupSize(1)
        .withMaxRequestsPerWorker(100)
        .withReset(worker -> worker.request("reset"))
        .open()) {
    LineResponse response = pool.request("status", Duration.ofSeconds(2));
    PooledLineSessionMetrics metrics = pool.metrics();
    if (response.text().isBlank() || metrics.size() > 4) {
        throw new IllegalStateException("unexpected pooled response");
    }
}
```

More examples: [Examples](../examples.md#worker-pool).

Typed protocol pools use a per-worker adapter factory so adapter state is never shared between workers.

```java
CommandService worker = Icli.command("tool");

try (PooledProtocolSession<String, String> pool = worker.protocolSession(LengthPrefixedTextAdapter::new)
        .withArgs("worker")
        .withReadiness(ready -> ready.request("ready"))
        .pooled()
        .withMaxSize(4)
        .withWarmupSize(1)
        .withMinIdle(1)
        .open()) {
    String response = pool.request("document\nbody", Duration.ofSeconds(2));
    PooledProtocolSessionMetrics metrics = pool.metrics();
    if (response.isBlank() || metrics.size() > 4) {
        throw new IllegalStateException("unexpected pooled response");
    }
}
```

## Use this scenario because

The pool owns worker acquisition, reuse, retirement, and graceful close. The caller still owns protocol reuse rules and
reset semantics.
