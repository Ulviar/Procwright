# Protocol Sessions

Use `protocolSession` when a long-lived CLI worker speaks a request/response protocol that is not a single stdout line.
The caller supplies a `ProtocolAdapter<I, O>` that owns request framing and response decoding.

The scenario covers:

- multi-line, byte, or typed requests;
- deadline-aware stdin writing and stdout/stderr reading;
- one in-flight request per worker;
- request timeout, acquire timeout in pools, and readiness timeout as separate failures;
- strict or replacing charset decoding;
- request and response size limits;
- bounded transcripts with malformed/truncated markers;
- process close after protocol failure.

Compile-tested source: `CommandServiceApiExamples.protocolSessionScenario`.

## Pooling

Use `protocolSession(factory).pooled()` when worker startup is expensive and the adapter can prove that a worker is
reusable after each request. The pool owns acquire, release, retirement, reset, health checks, warmup, and background
replenishment. The pooled API takes an adapter factory so each worker owns its own protocol state. iCLI serializes
factory calls, and the adapters returned by the factory do not need to be thread-safe.

Compile-tested source: `CommandServiceApiExamples.pooledProtocolSessionScenario`.

## Adapter Boundary

The adapter should describe protocol framing, not process lifecycle. It should write exactly one request, flush when the
protocol requires it, and read exactly one response. iCLI owns the process, deadlines, output pumps, bounded diagnostics,
and worker retirement after failures.

Use the optional integrations module for common adapter helpers: JSON Lines, delimiter-framed bytes, Content-Length JSON,
and typed JSON mapping.
