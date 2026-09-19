# Protocol sessions

A protocol session keeps one worker alive and maps typed requests and responses to its wire format. Each request writes
one message and reads one reply; concurrent requests on the session are serialized.

## Ready-made protocols

For JSON Lines, delimiter-framed bytes, or Content-Length JSON, start with the
[ready-made adapters](integrations.md). The [typed JSON Lines walkthrough](../how-to/wrap-cli-tool.md) turns a worker into
a service without implementing a framing adapter.

## Custom framing

Implement `ProtocolAdapter<I, O>` when the worker uses another wire format. `writeRequest` encodes one request;
`readResponse` consumes exactly one reply. This example sends a UTF-8 byte count followed by text, including embedded
newlines:

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/ProtocolSessionExample.java#request -->
```java
try (ProtocolSession<DocumentRequest, DocumentResponse> session = Procwright.command(
                ExampleSupport.workerCommand("protocol"))
        .protocolSession(LengthLineFrameAdapter::new)
        .withRequestTimeout(Duration.ofSeconds(5))
        .withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8))
        .withMaxRequestBytes(16_384)
        .withMaxRequestChars(8192)
        .withMaxResponseBytes(16_384)
        .withMaxResponseChars(8192)
        .open()) {
    DocumentResponse response = session.request(new DocumentRequest("first line\nПривет, 世界"));
    if (!response.text().equals("first line\nПривет, 世界")) {
        throw new IllegalStateException("Unexpected protocol response");
    }
}
```

[Client and imports](../examples/java/io/github/ulviar/procwright/examples/ProtocolSessionExample.java) ·
[Request and response records](../examples/java/io/github/ulviar/procwright/examples/DocumentProtocol.java) ·
[Adapter](../examples/java/io/github/ulviar/procwright/examples/LengthLineFrameAdapter.java) ·
[Worker](../examples/java/io/github/ulviar/procwright/examples/ExampleWorker.java)

The adapter writes `<bytes>\n<body>` and reads `len:<bytes>\n<body>\nEND\n`. Lengths count UTF-8 bytes, not Java characters.
It bounds the response header at 64 characters and the response body at 8,192 bytes and 4,096 decoded characters. The
worker must flush each reply. Replace `ExampleSupport.workerCommand("protocol")` with a `CommandSpec` for your executable,
and change the adapter to match its format. See [running the examples](../examples.md#core) for the shared support sources.

## Adapter ownership

Pass an adapter factory, such as `LengthLineFrameAdapter::new`, that returns a fresh adapter for every session or pool
worker. Factories may run concurrently. Create mutable adapter state inside the factory; captured mutable state remains
shared. A factory exception or `null` result fails before a process starts.

Within one session, writing finishes before response decoding starts, and adapter callbacks do not overlap across
requests. Different sessions can run concurrently. Readiness, diagnostics recipients, and a custom PTY provider retain
the instances supplied to the Draft, so make those callbacks thread-safe or use separate instances.

`ProtocolWriter` and `ProtocolReader` are valid only inside their adapter callback, on its thread. Do not save them or
pass them to another thread. A late or cross-thread call fails before touching process I/O.

## Read text and bytes

Choose the reader method that matches the wire format:

| Field | Reader method |
| --- | --- |
| LF-terminated text, with optional CR before LF | `readLine(maxChars)` |
| A complete text field with a declared byte length | `readTextExactly(byteLength, maxChars)` |
| Text through a single-byte delimiter, including that delimiter | `readTextUntil(delimiter, maxChars)` |
| Raw bytes | `readExactly(length)`, `readUntil(delimiter, maxBytes)`, or `read(...)` |

Continuous text reads share decoder state. Exact text fields use a separate decoder; raw reads do not decode text or
consume the character budget. Switch between these modes only at complete character boundaries. Procwright rejects a
mode switch while continuous decoding has an incomplete character or unread line output. After arbitrary raw bytes,
the adapter must establish the character boundary itself.

Text methods apply the selected `CharsetPolicy`. Use strict decoding, as above, when malformed text should fail the
protocol. A character-limit failure in `readTextExactly` stops reading and closes the session; it does not drain the
remaining field.

## Limits and diagnostics

Set `withMaxRequestBytes(...)` and `withMaxResponseBytes(...)` to fit complete messages, including headers and delimiters.
Response reads share one byte budget across stdout and stderr. Text reads also count toward the response character
budget; each reader call's local limit applies in addition. These limits bound I/O, not the total memory allocated by
adapter code. See [protocol defaults](../reference/defaults.md#protocol-sessions).

Each unread stdout/stderr queue uses the response byte limit. Excess response or queued stdout produces
`RESPONSE_TOO_LARGE` and closes the session. Keep worker logs on stderr: its overflow fails a request only if the adapter
reads stderr, while the retained diagnostic transcript remains independently bounded.

## Request failures

A timeout or interruption while waiting behind another request sends no bytes and leaves a healthy direct session open.
If the session has already failed, its selected failure takes precedence.

Once the request acquires its turn, any timeout, interruption, callback-start failure, or protocol failure closes the
session. At that point, failure cannot prove that the worker received no input or performed no work. Reopen the session
before further requests, and do not blindly retry work that may have side effects.

The selected `ProtocolSessionException` completes a still-pending `onExit()` exceptionally. An adapter that ignores
interruption may continue after a timeout; `onExit()` does not wait for it or a blocked physical stream close. Its eventual
result cannot replace the selected failure. See [results and errors](../reference/results-and-errors.md#sessions) for
reason codes and exit-code availability.
