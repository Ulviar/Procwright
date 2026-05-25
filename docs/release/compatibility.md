# Compatibility

## 0.1.0 baseline

- Runtime JDK: Java 17 or newer.
- Public artifact bytecode target: Java 17.
- Source evaluation can also be run with Java 21 or 25 by passing `--project-prop=icli.javaRelease=21` or `25` while
  using the matching JDK.
- Current version: `0.1.0`.

## Modules

- `io.github.ulviar:icli` is the Java core artifact and named module `io.github.ulviar.icli`.
- `io.github.ulviar:icli-kotlin` is optional Kotlin ergonomics.
- `io.github.ulviar:icli-integrations` is optional Java integration helpers and named module
  `io.github.ulviar.icli.integrations`.

The Java core module has no runtime dependencies outside the JDK and exports only public API packages. The integrations
module exports only `io.github.ulviar.icli.integration` and requires the core module transitively because its public
helpers expose core protocol/session types.

The documented public type set is described in [API Baseline](api-baseline.md).

## Platform behavior

Ordinary process execution is expected to work through JDK process APIs. Terminal capability depends on platform
support and the configured `PtyProvider`.

On Java 21 and newer runtimes, iCLI may use virtual threads internally. Java 17 uses a daemon platform-thread fallback.
This is an implementation detail, not a public API contract.

Windows ConPTY support is not a shipped provider in `0.1.0`. Terminal-required workflows must fail
explicitly when no configured provider is available; they must not silently fall back to ordinary pipes.

## Compatibility before 1.0

The project may still make breaking public API changes before `1.0.0`. Breaking changes should be reflected in examples,
public docs, and this compatibility policy.

Session-family handles are sealed interfaces backed by hidden iCLI implementations. Create them through
`Icli.command(...)` scenario methods; custom handle implementations are not supported.

For `0.1.0`, `Icli.command(...)` is the recommended entry point, `CommandService` remains the reusable command handle,
`SessionOptions.idleTimeout` keeps its caller-visible activity semantics, and the current `ScenarioPresets` set is part
of the public pre-1.0 API. The `0.1.0` compatibility scope covers the scenario call shapes for `run`, `interactive`,
`lineSession`, `protocolSession`, `lineSession().pooled()`, and `protocolSession(factory).pooled()`.
