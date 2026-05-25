# Terminal Capability

Terminal support is a capability inside session-family scenarios. It is not a separate core scenario.

Use terminal capability when a CLI changes behavior based on whether it is attached to a terminal, or when the command
requires terminal control sequences to function.

## Policies

- `TerminalPolicy.DISABLED` runs with ordinary pipes and never requests a terminal.
- `TerminalPolicy.AUTO` lets the scenario use a terminal when a terminal-capable transport exists.
- `TerminalPolicy.REQUIRED` fails instead of silently falling back to pipes.

## Example

```java
CommandService shell = Icli.command("sh");

try (Session session = shell.interactive().withTerminal(TerminalPolicy.REQUIRED).open()) {
    session.sendSignal(TerminalSignal.INTERRUPT);
}
```

Compile-tested source: `CommandServiceApiExamples.terminalRequiredSessionScenario`.

## Boundary

The current core API exposes terminal policy, terminal size, terminal control signals, and a narrow `PtyProvider` SPI.
It does not expose backend-specific PTY library types.

The initial system provider is platform-dependent. Unix-like environments depend on an available system terminal helper
such as `script(1)`. Windows ConPTY is not shipped as a provider in `0.1.0`.

Code that requires a terminal should use `REQUIRED` so unavailable terminal support is visible as a failure instead of
an accidental pipe fallback. See [Platforms and PTY](../reference/platforms-and-pty.md) for the platform boundary.
