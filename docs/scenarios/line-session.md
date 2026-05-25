# Line Sessions

Use `lineSession` when a CLI behaves like a line-oriented request/response worker.

The scenario covers:

- serialized requests;
- default and custom response decoders;
- bounded transcript diagnostics;
- bounded stdout line length;
- per-request timeout;
- EOF and timeout distinction;
- stderr draining.

## Example shape

```java
CommandService repl = Icli.command(CommandSpec.of("tool"));

try (LineSession session = repl.lineSession()
        .withArgs("repl")
        .withRequestTimeout(Duration.ofSeconds(2))
        .open()) {
    LineResponse response = session.request("status");
    if (response.text().isBlank()) {
        throw new IllegalStateException("empty response");
    }
}
```

Complete example source: [`CommandServiceApiExamples.lineSessionScenario`](https://github.com/Ulviar/iCLI/blob/main/src/test/java/io/github/ulviar/icli/examples/CommandServiceApiExamples.java).

## Failure model

`LineSessionException` exposes a reason and a bounded transcript snapshot. The reason distinguishes request timeout,
EOF before a complete response, closed session, and read or decoder failure.

After request timeout or failure, the session is closed. iCLI does this because the protocol state may no longer be
known.

## When not to use it

Do not use `lineSession` for arbitrary terminal parsing or for tools that can emit interleaved prompts without a stable
line protocol. Use `interactive` with `Expect` for prompt-oriented automation.
