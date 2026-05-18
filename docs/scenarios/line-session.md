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
try (LineSession session = repl.lineSession(call -> call.args("repl"))) {
    LineResponse response = session.request("status");
    if (response.text().isBlank()) {
        throw new IllegalStateException("empty response");
    }
}
```

Compile-tested source: `CommandServiceApiExamples.lineSessionScenario`.

## Failure model

`LineSessionException` exposes a reason and a bounded transcript snapshot. The reason distinguishes request timeout,
EOF before a complete response, closed session, and read or decoder failure.

After request timeout or failure, the session is closed. iCLI does this because the protocol state may no longer be
known.

## When not to use it

Do not use `lineSession` for arbitrary terminal parsing or for tools that can emit interleaved prompts without a stable
line protocol. Use `interactive` with `Expect` for prompt-oriented automation.
