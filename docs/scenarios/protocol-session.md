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

Complete example source: [`CommandServiceApiExamples.protocolSessionScenario`](https://github.com/Ulviar/iCLI/blob/main/src/test/java/io/github/ulviar/icli/examples/CommandServiceApiExamples.java).

## Example

```java
CommandService worker = Icli.command("tool");
ProtocolAdapter<String, String> adapter = new LengthPrefixedTextAdapter();

try (ProtocolSession<String, String> session = worker.protocolSession(adapter)
        .withArgs("worker")
        .withRequestTimeout(Duration.ofSeconds(2))
        .withOutputBacklogLimit(128 * 1024)
        .withReadiness(ready -> ready.request("ready"))
        .open()) {
    String response = session.request("first line\nsecond line");
    if (response.isBlank()) {
        throw new IllegalStateException("empty response");
    }
}
```

The adapter owns the protocol framing:

```java
private static final class LengthPrefixedTextAdapter implements ProtocolAdapter<String, String> {

    @Override
    public void writeRequest(String request, ProtocolWriter writer) {
        byte[] body = request.getBytes(StandardCharsets.UTF_8);
        writer.writeLine(Integer.toString(body.length));
        writer.write(body);
        writer.flush();
    }

    @Override
    public String readResponse(ProtocolReaders readers) {
        ProtocolReader stdout = readers.stdout();
        int length = Integer.parseInt(stdout.readLine(32));
        byte[] body = stdout.readExactly(length);
        return new String(body, StandardCharsets.UTF_8);
    }
}
```

## Pooling

Use `protocolSession(factory).pooled()` when worker startup is expensive and the adapter can prove that a worker is
reusable after each request. The pool owns acquire, release, retirement, reset, health checks, warmup, and background
replenishment. The pooled API takes an adapter factory so each worker owns its own protocol state. iCLI serializes
factory calls, and the adapters returned by the factory do not need to be thread-safe.

Complete example source: [`CommandServiceApiExamples.pooledProtocolSessionScenario`](https://github.com/Ulviar/iCLI/blob/main/src/test/java/io/github/ulviar/icli/examples/CommandServiceApiExamples.java).

## Adapter Boundary

The adapter should describe protocol framing, not process lifecycle. It should write exactly one request, flush when the
protocol requires it, and read exactly one response. iCLI owns the process, deadlines, output pumps, bounded diagnostics,
and worker retirement after failures.

Use the optional integrations module for common adapter helpers: JSON Lines, delimiter-framed bytes, Content-Length JSON,
and typed JSON mapping.
