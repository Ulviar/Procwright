# Interactive Sessions

Use `interactive` when the caller needs direct control over a live process through stdin/stdout/stderr.

The scenario covers:

- owned process lifecycle through `Session`;
- guarded stdin writes;
- explicit output ownership handoff to higher-level helpers;
- caller-visible idle timeout;
- idempotent close;
- optional terminal capability for session-family workflows.

## Example

```java
CommandService python =
        Procwright.command(CommandSpec.of("python")).withSessionOptions(SessionOptions.defaults()
                .withIdleTimeout(Duration.ofMinutes(5)));

try (Session session = python.interactive().withArgs("-i").open()) {
    session.sendLine("print(6 * 7)");
    session.closeStdin();
    SessionExit exit = session.onExit().join();
    if (exit.timedOut()) {
        throw new IllegalStateException("session timed out");
    }
}
```

`closeStdin()` signals end-of-input immediately and closes the raw stream in the background, so it never blocks — even
while a concurrent write is stuck on a full stdin pipe; writes after `closeStdin()` fail with `IllegalStateException`.

## Output ownership

Interactive sessions expose raw stdout and stderr, but Procwright still protects output ownership. The first public raw stream
operation chooses raw stream mode. A higher-level helper such as `Expect`, `LineSession`, or `StreamSession` must claim
output before raw stream access starts.

This rule prevents two readers from competing for bytes from the same process.

## Expect automation

`Expect` builds prompt automation on top of an already opened `Session`. It claims output ownership, waits for literal
or regex output, sends input, and reports timeout or EOF with a bounded transcript.

See [Expect Automation](expect.md) for the prompt-specific contract.

## Failure model

`Session.onExit()` exposes a `SessionExit` with an optional exit code and a timeout flag. Lifecycle shutdown from
`close()`, idle timeout, or failure uses the configured `ShutdownPolicy`.

Raw sessions do not serialize request/response operations. If a protocol has a line request/response shape, prefer
`lineSession`.
