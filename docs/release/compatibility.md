# Compatibility

## Runtime

- Runtime JDK: Java 17 or newer.
- Public artifact bytecode target: Java 17.
- Current version: `0.1.0`.

## Modules

- `io.github.ulviar:procwright` is the Java core artifact.
- `io.github.ulviar:procwright-kotlin` is optional Kotlin ergonomics.
- `io.github.ulviar:procwright-integrations` is optional Java integration helpers.

The Java core module has no runtime dependencies outside the JDK. Optional modules add dependencies only when you use
those artifacts.

The documented public API surface is described in [API Surface](api-baseline.md).

## Platform behavior

Ordinary process execution is expected to work through JDK process APIs. Terminal capability depends on platform
support and the configured `PtyProvider`.

On Java 21 and newer runtimes, Procwright may use virtual threads internally. Java 17 uses a daemon platform-thread fallback.
This is an implementation detail, not a public API contract.

Windows ConPTY support is not a shipped provider in `0.1.0`. Terminal-required workflows must fail
explicitly when no configured provider is available; they must not silently fall back to ordinary pipes.

## Compatibility before 1.0

The project may still make breaking public API changes before `1.0.0`. Breaking changes will be documented in examples,
public docs, and this compatibility policy.

Create session-family handles through `Procwright.command(...)` scenario methods; custom handle implementations are not
supported.

For `0.1.0`, `Procwright.command(...)` is the recommended entry point and `CommandService` is the reusable command handle. The
compatibility scope covers the scenario call shapes listed in [API Surface](api-baseline.md).
