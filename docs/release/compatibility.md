# Compatibility

## Runtime and artifacts

- Runtime JDK: Java 25.
- Artifact bytecode target: Java 25.
- Current status: no public release; `0.1.0` is the planned first version.
- Core coordinates: `io.github.ulviar:procwright`.
- Optional coordinates: `io.github.ulviar:procwright-kotlin` and
  `io.github.ulviar:procwright-integrations`.

Core has no runtime dependency outside the JDK. Its public packages use JSpecify nullness annotations, supplied as
compile-time dependency metadata. The Kotlin module uses Kotlin 2.4.20 and exposes
`kotlinx-coroutines-core` 1.11.0 transitively; consumers need a compiler that can read Kotlin 2.4 metadata. The
integrations module uses Jackson 3 `tools.jackson.databind.JsonNode` as its JSON protocol payload and exposes Jackson Databind 3.2.2
transitively. Java 25 is the sole supported runtime and build toolchain; CI covers Linux, macOS, and Windows.

## Public API boundary

The supported entry point is `Procwright.command(...)`. Scenario methods return persistent Drafts; only `execute()` and
`open()` start processes. The documented scenario surface includes `run`, `interactive`, `Expect`, `lineSession`,
factory-backed `protocolSession`, `listen`, line and protocol pools, and the optional module APIs.

Session handles are sealed and are not extension points. Backend-specific process types and implementation classes
are not supported application APIs.

Before `1.0.0`, public signatures can change between releases. Code should use stable reason enums rather than exception
messages. Compatibility decisions are reflected in the current API docs and compile-tested examples.

## Platform boundary

Ordinary process execution uses JDK process APIs. Shell syntax and terminal capability remain platform-specific. The built-in provider does not support Windows
ConPTY; `TerminalPolicy.REQUIRED` fails when no provider can supply a terminal.
