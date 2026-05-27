# Platforms and PTY

Procwright core is built on JDK process APIs and keeps platform-specific terminal behavior behind a narrow capability
boundary.

## Java runtime

Procwright consumers use one public artifact that targets Java 17. Run it on Java 17 or newer.

On Java 21 and newer runtimes, Procwright may use virtual threads behind an internal runtime boundary. The Java 17 variant
uses daemon platform-thread fallback. The public API does not expose or require a specific threading implementation.

## Ordinary process execution

The core one-shot and session scenarios use ordinary process pipes by default.

## PTY boundary

Terminal capability is requested through `TerminalPolicy` inside session-family scenarios. The public API exposes
terminal policy, requested terminal size, terminal control signals, and `PtyProvider`.

Application code does not need backend-specific PTY library classes when it uses the core terminal API.

`0.1.0` does not ship a Windows ConPTY provider. ConPTY support should be added as a separate optional
provider or runtime-specific artifact without changing the scenario API or adding native dependencies to the core
module.

The system provider for Unix-like environments depends on an available terminal helper such as `script(1)`. If that
capability is unavailable, terminal-required scenarios should fail explicitly.

## Required terminal mode

Use `TerminalPolicy.REQUIRED` when terminal behavior is mandatory. This makes unavailable terminal support visible
instead of silently falling back to ordinary pipes.
