# Require a Terminal

Use `interactive` with `TerminalPolicy.REQUIRED` when a command must run with terminal capability.

## Steps

1. Keep the workflow in the session family.
2. Request terminal capability through `TerminalPolicy.REQUIRED`.
3. Handle terminal unavailability as a real launch/runtime failure.
4. Avoid depending on backend-specific PTY types in application code.

```java
CommandService shell = Icli.command("sh");

try (Session session = shell.interactive().withTerminal(TerminalPolicy.REQUIRED).open()) {
    session.sendLine("exit");
    SessionExit exit = session.onExit().join();
    if (exit.timedOut()) {
        session.sendSignal(TerminalSignal.INTERRUPT);
    }
}
```

More examples: [Examples](../examples.md#core-examples).

## Use this scenario because

Terminal capability is part of session-family lifecycle. `run` and `listen` do not expose PTY controls in the current
public API.

Current terminal support is intentionally narrow. Unix-like environments depend on an available system terminal helper
such as `script(1)`, and Windows ConPTY is not shipped in `0.1.0`. Use `REQUIRED` only when the caller
can handle explicit terminal-unavailable failure.
