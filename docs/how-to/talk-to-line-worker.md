# Talk to a Line Worker

Use `lineSession` when a command stays alive and answers one logical request at a time over a line-oriented protocol.

## Steps

1. Configure `LineSessionOptions` for request timeout, transcript limits, and decoder behavior.
2. Open a line session for the worker command.
3. Send requests through `LineSession.request(...)`.
4. Treat request timeout or decoder failure as a session-ending event.

```java
CommandService repl = Icli.command(CommandSpec.of("tool"));

try (LineSession session =
        repl.lineSession().withArgs("repl").withRequestTimeout(Duration.ofSeconds(2)).open()) {
    LineResponse response = session.request("status");
    if (response.text().isBlank()) {
        throw new IllegalStateException("empty response");
    }
}
```

Compile-tested source: `CommandServiceApiExamples.lineSessionScenario`.

## Use this scenario because

`lineSession` owns request serialization, response decoding, timeout/EOF distinction, stderr draining, and bounded
transcripts. Raw `interactive` sessions are a better fit only when the protocol is not line request/response shaped.
