# Talk to a line worker

Keep one process open and send it repeated text requests with `lineSession()`. Each request must fit on one line. By
default, the next stdout line is the response; a custom decoder can collect several response lines.

The worker must flush each reply and reserve stdout for responses. Send logs to stderr.

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/LineSessionExample.java#request -->
```java
try (LineSession session = Procwright.command(ExampleSupport.workerCommand("line"))
        .lineSession()
        .withRequestTimeout(Duration.ofSeconds(5))
        .open()) {
    LineResponse response = session.request("Zażółć gęślą jaźń");
    if (!response.text().equals("response:Zażółć gęślą jaźń")) {
        throw new IllegalStateException("Unexpected line response");
    }
}
```

[Open `LineSessionExample.java`](../examples/java/io/github/ulviar/procwright/examples/LineSessionExample.java) and the
[shared example sources](../examples.md#core).

Replace `ExampleSupport.workerCommand("line")` with `CommandSpec.of("my-worker").withArgs("--line-mode")` for a worker
with the same protocol. Import `io.github.ulviar.procwright.command.CommandSpec`. Pass request text without a line
terminator; Procwright appends LF. The included worker replies with `response:<request>` followed by LF.

Call `session.request(...)` again to reuse the same process. Concurrent calls are serialized. The default request timeout
is five seconds, and try-with-resources closes the worker when you finish.

The default response limit is 1,024 lines and 1,048,576 characters per exchange. Unread stdout uses the same bounds. See
[line-session contracts](../scenarios/line-session.md) for multiline decoding, limits, and when a failed request leaves
the session reusable.

For requests containing newlines or framing based on bytes rather than lines, use a
[protocol session](../scenarios/protocol-session.md). For a JSON Lines worker with typed requests and responses, follow
the [service walkthrough](wrap-cli-tool.md).
