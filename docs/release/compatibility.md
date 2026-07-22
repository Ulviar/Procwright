# Compatibility

## Runtime and artifacts

- Runtime JDK: Java 17 or newer.
- Artifact bytecode target: Java 17.
- Current status: no public release; `0.1.0` is the planned first version.
- Core coordinates: `io.github.ulviar:procwright`.
- Optional coordinates: `io.github.ulviar:procwright-kotlin` and
  `io.github.ulviar:procwright-integrations`.

Core has no runtime dependency outside the JDK. The Kotlin module uses Kotlin 2.3.21 and exposes
`kotlinx-coroutines-core` 1.11.0 transitively; consumers need a compiler that can read Kotlin 2.3 metadata. The
integrations module exposes Jackson Databind 2.22.0 for its optional `JsonNode` bridge.

## Public API boundary

The supported entry point is `Procwright.command(...)`. Scenario methods return persistent Drafts; only `execute()` and
`open()` are terminals. The documented scenario surface includes `run`, `interactive`, `Expect`, `lineSession`,
factory-backed `protocolSession`, `listen`, line and protocol pools, presets, and the optional module APIs.

Session handles are sealed and are not extension points. Backend-specific process types and implementation classes
remain inaccessible, but changes to the JVM permitted-subclass metadata of a public handle are treated as compatibility
changes.

Before `1.0.0`, public signatures can change between releases. Code should use stable reason enums rather than exception
messages. Compatibility decisions are reflected in the current API docs and compile-tested examples.

## Platform boundary

Ordinary process execution uses JDK process APIs. Shell syntax and terminal capability remain platform-specific. Windows
ConPTY support is not shipped in planned `0.1.0`; `TerminalPolicy.REQUIRED` fails when no provider can supply a terminal.
