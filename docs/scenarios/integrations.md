# Integrations

The optional `procwright-integrations` module maps structured Java values onto JSON Lines, delimiter-framed bytes, and
Content-Length JSON without creating a second process runtime.

Add the integrations artifact alongside core:

```kotlin
dependencies {
    implementation("io.github.ulviar:procwright-integrations:0.1.0")
}
```

See [installation](../release/installation.md#optional-modules) for Maven and Gradle Groovy syntax.

## Choose a transport

| Worker protocol | Factory or wrapper | Complete example |
| --- | --- | --- |
| One JSON value per line | `jsonLinesSession(...)` or `JsonLineSession` | [JSON Lines](../examples/integrations/io/github/ulviar/procwright/examples/integration/JsonLineIntegrationExample.java) |
| Delimiter-framed bytes | `delimiterSession(...)` | [JSON Lines and delimiter transports](../examples/integrations/io/github/ulviar/procwright/examples/integration/JsonLineIntegrationExample.java) |
| Content-Length JSON with domain types | `typedJsonSession(..., contentLengthJsonSession(...))` | [Typed Content-Length session](../how-to/wrap-cli-tool.md) |

```java
--8<-- "examples/integrations/io/github/ulviar/procwright/examples/integration/TypedContentLengthJsonSessionExample.java"
```

`ProtocolAdapters.jsonLinesSession(...)`, `delimiterSession(...)`, and `contentLengthJsonSession(...)` return factories
that can be passed directly to `protocolSession(...)` and then to `pooled()`. Every factory call creates a fresh adapter,
so separate sessions and pool workers do not share framing state. `typedJsonSession(...)` preserves the same invariant by
accepting a transport factory and creating a fresh typed wrapper for each call.

`typedJsonSession(...)` retains its encode, decode, and transport-factory callbacks. Separate sessions and pool workers
can invoke the same callback objects concurrently, so applications must make them thread-safe and create mutable
per-adapter state inside each transport-factory call. The helper does not synchronize callbacks across workers. A null
transport, encoded JSON value, or decoded domain result fails closed; runtime callback failures retain core's distinct
request-write and response-decoder reasons, while callback `Error` values are propagated.

The application still chooses the executable and owns its domain mapping. Integration exceptions preserve process and
protocol failures so callers do not need message matching. The module exposes Jackson Databind for its optional
`JsonNode` bridge; check application dependency constraints before adding it.

`JsonValue` accepts BMP characters and valid UTF-16 surrogate pairs. It rejects unpaired surrogates in string values and
object member names with `JsonParseException` before JSON Lines or Content-Length framing starts, so every transport
serializes the same Unicode scalar values without replacement characters.

## Content-Length JSON wire contract

The adapter writes exactly `Content-Length: N\r\n\r\n<body>`. The header is US-ASCII with that casing; `N` is the
decimal count of UTF-8 body bytes, not Java characters. The body is one JSON value.

Responses use this grammar:

```text
frame        = header-line *(header-line) CRLF body
header-line  = field-name ":" field-value CRLF
field-name   = 1*(ALPHA / DIGIT / "!" / "#" / "$" / "%" / "&" / "'" / "*" /
                  "+" / "-" / "." / "^" / "_" / "`" / "|" / "~")
field-value  = *(HTAB / %x20-7E)
```

Exactly one case-insensitive `Content-Length` field is required. Its value is optional spaces or tabs, one or more
unsigned ASCII decimal digits in the Java `int` range, then optional spaces or tabs. Additional well-formed headers are
ignored. Header names cannot contain whitespace before `:`, folded lines are rejected, every line uses CRLF, and the
empty CRLF line ends the block. All other control and non-ASCII header bytes are rejected.

The complete header block, including its final `\r\n\r\n`, may contain at most 8192 bytes; a terminator ending at byte
8192 is valid. The adapter then reads exactly `N` raw body bytes, decodes them with strict UTF-8, and parses one complete
JSON value with no trailing content.

## Limits

| Setting | What it counts for this transport |
| --- | --- |
| `contentLengthJsonSession(maxFrameBytes)` | Declared response body bytes only; it does not include response headers or limit requests. |
| `withMaxRequestBytes(...)` | The complete emitted request frame: generated header plus UTF-8 JSON body. |
| `withMaxResponseBytes(...)` | All adapter-consumed response bytes: header block plus body. |
| `withOutputBacklogLimit(...)` | Unread process output bytes; keep it large enough for the expected frame. |
| `withMaxRequestChars(...)`, `withMaxResponseChars(...)` | Text API calls only. This adapter uses raw bytes, so these limits do not govern its JSON. |
| `withCharsetPolicy(...)` | Text reads and transcripts only. The JSON body is always strict UTF-8, even if the scenario policy replaces malformed text. |

Set both byte layers. For a body limit `B`, allow request wire bytes for the generated header plus `B`, and response wire
bytes for up to 8192 header bytes plus `B`. The example computes the canonical ASCII request header at `B` and uses
`Math.addExact` to include it in the request wire limit; a larger body therefore exceeds that limit.

## Failures

`session.request(...)` exposes adapter failures through the outer `ProtocolSessionException`:

| Failure | Outer reason | Cause | Direct session |
| --- | --- | --- | --- |
| Encoder creates a string or member name with an unpaired UTF-16 surrogate | `FAILURE` | `JsonParseException` | Terminal after admission |
| Response body is malformed JSON | `PROTOCOL_DECODER_FAILED` | `JsonParseException` | Terminal |
| Header syntax, required length, decimal length, or declared response body limit is invalid | `PROTOCOL_DECODER_FAILED` | `IntegrationProtocolException` with `BAD_HEADER`, `MISSING_LENGTH`, `BAD_LENGTH`, or `OVERSIZED_FRAME` | Terminal |
| Response body is not valid UTF-8 | `PROTOCOL_DECODER_FAILED` | `IntegrationProtocolException` with `INVALID_ENCODING` | Terminal |
| Stdout closes before the response header or body is complete | `EOF` or `PROCESS_EXITED`, according to observation order | No stable adapter framing cause; branch on the outer reason | Terminal |

An admitted protocol request failure closes a direct session, even when encoding failed before writing bytes. Do not retry
on that session; open another one. Scenario limits and process failures retain their own outer reasons, such as
`REQUEST_TOO_LARGE`, `RESPONSE_TOO_LARGE`, `TIMEOUT`, or `PROCESS_EXITED`.
