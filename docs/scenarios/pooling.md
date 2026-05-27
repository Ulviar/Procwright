# Pooling

Use `lineSession().pooled()` when a line-oriented worker is expensive to start and the protocol can be reliably reused
between requests. Use `protocolSession(factory).pooled()` for framed, multi-line, binary, or typed workers.

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

For typed protocol workers, see [Protocol Sessions](protocol-session.md).

## Example

```java
CommandService tool = Procwright.command("tool");

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

## User responsibilities

The caller owns protocol reuse rules. A worker should be pooled only when reset and health semantics are clear. If a
request timeout, decoder failure, failed reset, failed health check, request limit, or worker age limit makes another
request unreliable, Procwright retires the worker.

`protocolSession(factory).pooled()` uses an adapter factory, not a shared adapter instance. Procwright serializes factory
calls; each worker owns one returned adapter and one process protocol state.

Pooling is intentionally scenario-specific. Procwright has line-session and typed protocol pools because those scenarios have
clear request boundaries.
