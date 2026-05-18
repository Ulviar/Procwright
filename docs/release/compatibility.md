# Compatibility

## Current baseline

- Build JDK: 25 or newer.
- Java bytecode target: 25.
- Current version: `0.0.0-SNAPSHOT`.

## Modules

- `com.github.ulviar:icli` is the Java core artifact and named module `com.github.ulviar.icli`.
- `com.github.ulviar:icli-kotlin` is optional Kotlin ergonomics.
- `com.github.ulviar:icli-integrations` is optional Java integration helpers.

The Java core module has no runtime dependencies outside the JDK and exports only public API packages.

## Platform behavior

Ordinary process execution is expected to work through JDK process APIs. Terminal capability depends on platform
support and the configured `PtyProvider`.

Tests for machine-specific capabilities should skip through assumptions when a capability is unavailable.

## Compatibility before 1.0

The project may still make breaking public API changes before `1.0.0`. Breaking changes must update compile-tested
examples, public docs, and release notes.

The current rewrite includes a pre-1.0 session API break: session-family handles are interfaces backed by hidden iCLI
implementations. Create them through `CommandService`; custom handle implementations are not supported.
