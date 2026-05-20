# Known Limitations

This page tracks release-relevant limitations for the current pre-release baseline.

## Pre-release API

The API is not stable yet. Names, package structure, and option shapes may change before the first release candidate.

## Terminal support

Terminal capability is behind `TerminalPolicy` and `PtyProvider`. The current system provider is platform-dependent.
Windows ConPTY support is not documented as a shipped provider in this release state.

Terminal-required scenarios should fail explicitly when no provider is available. Silent fallback to ordinary pipes is
not part of the contract.

## Kotlin API docs

The optional Kotlin module has KDoc coverage checks, but generated Dokka publication is not part of the current public
site yet.

## Publishing

No Maven Central artifact is published from this branch yet. Planned coordinates are documented, but publishing,
signing, and final POM metadata remain a separate release implementation step.

## Java release variants

Java 17, 21, and 25 variants build from the same source tree. The Java 17 variant uses platform-thread fallback for
internal background work, so high-concurrency performance may differ from Java 21/25 runtimes that can use virtual
threads.

## Not in the current MVP

- Raw session pooling.
- Stateful affinity pools.
- A real MCP SDK adapter.
- Backend-specific process library APIs in core.
- Machine-dependent performance promises.

The broader scope boundary is described in [Non-goals](../explanations/non-goals.md).

## Documentation scope

Public docs describe implemented and tested behavior only. Internal `context/` documents may discuss future plans that
are not public guarantees.
