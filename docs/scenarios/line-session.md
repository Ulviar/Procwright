# Line sessions

A line session keeps one worker alive across requests. Each request is one line; the default response is the next
stdout line. Start with the [line-worker example](../how-to/talk-to-line-worker.md) for opening and closing a session.

## Read a multiline response

Use `withResponseDecoder(...)` when a reply spans a known number of lines or ends with a marker. This worker returns two
content lines followed by `END`. The decoder consumes the marker without including it in the result:

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/MultilineResponseExample.java#multiline -->
```java
try (LineSession session = Procwright.command(ExampleSupport.workerCommand("multiline"))
        .lineSession()
        .withMaxResponseLines(3)
        .withMaxResponseChars(1024)
        .withResponseDecoder(reader -> {
            List<String> lines = new ArrayList<>();
            String line;
            while (!(line = reader.readLine()).equals("END")) {
                lines.add(line);
            }
            return lines;
        })
        .open()) {
    for (String request : List.of("hello", "again")) {
        var response = session.request(request);
        if (!response.lines().equals(List.of("first:" + request, "second:" + request))) {
            throw new IllegalStateException("Unexpected multiline response");
        }
    }
}
```

[Complete example and imports](../examples/java/io/github/ulviar/procwright/examples/MultilineResponseExample.java) ·
[Worker source](../examples/java/io/github/ulviar/procwright/examples/ExampleWorker.java) ·
[Run the examples](../examples.md#core)

The three-line limit includes the terminator. A missing `END` reaches the response limit or the default five-second
request timeout; it cannot wait indefinitely. Choose a marker that cannot appear as an ordinary content line.
`LineResponse.lines()` contains the returned lines, and `text()` joins them with LF.

Requests cannot contain CR or LF. For multiline requests, byte-length framing, or binary data, use
[a protocol session](protocol-session.md). Keep logs on stderr so they cannot be mistaken for replies.

## Limits and decoding

`withMaxResponseChars(...)` and `withMaxResponseLines(...)` bound one exchange and the unread stdout queue. An unfinished
line uses the same character limit. LF/CRLF separators do not count as content characters; empty lines count toward the
line limit. Excess response or unread stdout produces `RESPONSE_TOO_LARGE` and closes the session. Unsolicited stdout
shares these bounds.

Request limits and the retained diagnostic transcript have separate bounds. `CharsetPolicy.report(...)` rejects
malformed text; `replace(...)` substitutes replacement characters. See [defaults](../reference/defaults.md#line-sessions)
for the initial limits and charset.

## Concurrent calls and callbacks

One session serializes requests. The Draft retains its decoder instance: opening several sessions or a pool can call
that decoder concurrently. Readiness, diagnostics recipients, and a custom PTY provider are also shared. Keep shared
callbacks thread-safe, or supply separate instances through separate Draft branches.

Use `ResponseDecoder.Reader` only inside the decoder callback, on the calling thread. Do not retain it or pass it to
another thread; such reads fail before consuming output.

## Request failures

Before a request is handed off for stdin writing, local validation, size checks, encoding, and wait failures leave the
direct session open if no later write can occur. That includes a timeout while waiting behind another request.

After handoff, a timeout, interruption, or write failure closes the session, even if the caller cannot confirm that the
worker received a byte. EOF, malformed output, oversized output, and decoder failures are also terminal. Do not blindly
retry work that may already have taken effect. Use the exception's reason and bounded transcript to diagnose the failure;
see [results and errors](../reference/results-and-errors.md#sessions).

A terminal failure completes a still-pending `onExit()` exceptionally. A callback that ignores interruption may continue
running after a timeout; `onExit()` does not wait for it or a blocked physical stream close. Its eventual result cannot
replace the selected failure.

## Wait for a worker to become ready

`withReadiness(...)` runs after launch and before `open()` returns. It can send a health request and reject an unexpected
reply. A failed or timed-out probe closes the process. Pool workers also pass readiness before becoming available.

The [readiness example](../examples/java/io/github/ulviar/procwright/examples/ReadinessExample.java) sends `health`, checks
`response:health`, and applies a five-second readiness timeout.
