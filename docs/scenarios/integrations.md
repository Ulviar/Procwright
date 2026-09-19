# Integrations

Use `procwright-integrations` when your worker speaks JSON Lines, delimiter-framed bytes, or Content-Length JSON. Its
adapter factories plug into the same `protocolSession(...)` API as custom adapters.

Add the integrations artifact; it includes core as a transitive dependency:

<!-- procwright-docs: build-configuration -->
```kotlin
dependencies {
    implementation("io.github.ulviar:procwright-integrations:0.1.0")
}
```

See [installation](../release/installation.md#optional-modules) for Maven and Gradle Groovy syntax.

## Choose a transport

| Worker protocol | Factory or wrapper | Complete example |
| --- | --- | --- |
| One JSON value per line | `jsonLines(...)` | [JSON Lines](../examples/integrations/io/github/ulviar/procwright/examples/integration/JsonLineIntegrationExample.java) |
| Delimiter-framed bytes | `delimited(...)` | [JSON Lines and delimiter transports](../examples/integrations/io/github/ulviar/procwright/examples/integration/JsonLineIntegrationExample.java) |
| Content-Length JSON with domain types | `typedJson(..., contentLengthJson(...))` | [Typed Content-Length session](../examples/integrations/io/github/ulviar/procwright/examples/integration/TypedContentLengthJsonSessionExample.java) |

Start with [a typed JSON Lines service](../how-to/wrap-cli-tool.md). Its configuration combines domain mapping with the
ready-made transport:

<!-- procwright-example: examples/integrations/io/github/ulviar/procwright/examples/integration/TextWorkerService.java#protocol -->
```java
public static ProtocolSessionScenario.Draft<Request, Metrics> draft(CommandSpec command) {
    var adapters = ProtocolAdapters.typedJson(
            (Request request) -> JsonNodeFactory.instance.objectNode().put("text", request.text()),
            response -> new Metrics(
                    response.required("codePoints").intValue(),
                    response.required("utf8Bytes").intValue()),
            ProtocolAdapters.jsonLines(64 * 1024));
    return Procwright.command(command).protocolSession(adapters);
}
```

Pass these factories directly to `protocolSession(...)`; add `pooled()` when requests can use interchangeable workers.
Each opened session or pool worker gets a fresh adapter. Use `typedJson(...)` to map between your domain objects and
Jackson `JsonNode` values. Jackson Databind is included transitively.

## JSON Lines and delimiter frames

`jsonLines(maxLineBytes)` reads and writes one JSON value per line using strict UTF-8, regardless of the scenario's text
charset. It writes LF and accepts LF or CRLF responses. The response limit includes the line ending.

`delimited(delimiter, maxFrameBytes)` appends one delimiter byte to each request and removes it from the response.
Request payloads must not contain that byte; the adapter does not escape it. The response limit includes the delimiter.

Both adapters also obey the scenario's `withMaxRequestBytes(...)` and `withMaxResponseBytes(...)` limits. Include framing
bytes in those limits. Their [complete example](../examples/integrations/io/github/ulviar/procwright/examples/integration/JsonLineIntegrationExample.java)
contains both worker implementations.

## Shared callbacks

`typedJson(...)` retains your encoder, decoder, and transport factory. Separate sessions and pool workers can invoke
those callbacks concurrently. Keep them thread-safe, and create mutable adapter state inside the transport factory.
Returning null for the transport, encoded value, or decoded result fails the operation.

## Content-Length JSON wire contract

`contentLengthJson(...)` writes `Content-Length: N\r\n\r\n<body>`. The header is US-ASCII with that casing; `N` is the
decimal count of UTF-8 body bytes, not Java characters. The body is one JSON value.

Exactly one case-insensitive `Content-Length` field is required. Its value is optional spaces or tabs, one or more
unsigned ASCII decimal digits in the Java `int` range, then optional spaces or tabs. Additional well-formed headers are
ignored. Header names cannot contain whitespace before `:`, folded lines are rejected, every line uses CRLF, and the
empty CRLF line ends the block. Header names use ASCII letters, digits, or `!#$%&'*+-.^_` plus backtick, `|`, and `~`;
values contain printable ASCII or tabs. Other control and non-ASCII header bytes are rejected.

The complete header block, including its final `\r\n\r\n`, may contain at most 8192 bytes; a terminator ending at byte
8192 is valid. The adapter then reads exactly `N` raw body bytes, decodes them with strict UTF-8, and parses one complete
JSON value with no trailing content.

## Content-Length limits

| Setting | What it counts for this transport |
| --- | --- |
| `contentLengthJson(maxFrameBytes)` | Declared response body bytes only; it does not include response headers or limit requests. |
| `withMaxRequestBytes(...)` | The complete emitted request frame: generated header plus UTF-8 JSON body. |
| `withMaxResponseBytes(...)` | All adapter-consumed response bytes: header block plus body. |
| `withMaxRequestChars(...)`, `withMaxResponseChars(...)` | Text API calls only. This adapter uses raw bytes, so these limits do not govern its JSON. |
| `withCharsetPolicy(...)` | Text reads and transcripts only. The JSON body is always strict UTF-8, even if the scenario policy replaces malformed text. |

The unread stdout/stderr queues follow `withMaxResponseBytes(...)` automatically.
For a body limit `B`, allow request bytes for the generated header plus `B`, and response bytes for up to 8192 header bytes
plus `B`. The [complete example](../examples/integrations/io/github/ulviar/procwright/examples/integration/TypedContentLengthJsonSessionExample.java)
uses `8192 + B` for both directions.

## Failures

`session.request(...)` exposes adapter failures through `ProtocolSessionException`. Inspect `reason()` and, when present,
the cause's `IntegrationProtocolException.reason()` instead of matching exception messages:

| Failure | Outer reason | Cause | Direct session |
| --- | --- | --- | --- |
| Request encoder or Jackson serialization fails | `FAILURE` | Original callback failure or `IntegrationProtocolException` with `BAD_FRAME` | Terminal after admission |
| Response body is malformed or contains trailing JSON | `PROTOCOL_DECODER_FAILED` | `IntegrationProtocolException` with `MALFORMED_JSON` | Terminal |
| Header syntax, required length, decimal length, or declared response body limit is invalid | `PROTOCOL_DECODER_FAILED` | `IntegrationProtocolException` with `BAD_HEADER`, `MISSING_LENGTH`, `BAD_LENGTH`, or `OVERSIZED_FRAME` | Terminal |
| Response body is not valid UTF-8 | `PROTOCOL_DECODER_FAILED` | `IntegrationProtocolException` with `INVALID_ENCODING` | Terminal |
| Stdout closes before the response header or body is complete | `EOF` or `PROCESS_EXITED`, according to observation order | No stable adapter framing cause; branch on the outer reason | Terminal |

An admitted protocol request failure closes a direct session, even when encoding failed before writing bytes. Do not retry
on that session; open another one. Scenario limits and process failures retain their own outer reasons, such as
`REQUEST_TOO_LARGE`, `RESPONSE_TOO_LARGE`, `TIMEOUT`, or `PROCESS_EXITED`.
