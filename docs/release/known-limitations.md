# Known Limitations

This page tracks release-relevant limitations for `0.1.0`.

## Pre-1.0 API

The API is public but still pre-1.0. Names, package structure, and option shapes may change before `1.0.0`.

## Terminal support

Terminal capability is behind `TerminalPolicy` and `PtyProvider`. The current system provider is platform-dependent.
Windows ConPTY support is not documented as a shipped provider in `0.1.0`.

Terminal-required scenarios should fail explicitly when no provider is available. Silent fallback to ordinary pipes is
not part of the contract.

## Kotlin generated docs

The optional Kotlin module is documented in [Kotlin API](../reference/kotlin-api.md) and checked through KDoc in source.
Generated Dokka publication is not part of the `0.1.0` release gate.

## Java release variants

Java 17, 21, and 25 variants build from the same source tree. The Java 17 variant uses platform-thread fallback for
internal background work, so high-concurrency performance may differ from Java 21/25 runtimes that can use virtual
threads.

## Process cleanup limits

One-shot timeout cleanup is covered by the `0.1.0` release gate, including process-tree cleanup through JDK `ProcessHandle`
descendant tracking. Session close and idle-timeout paths also shut down their owned process, but the release
contract should not be read as a universal containment guarantee for every process topology.

Detached descendants, inaccessible process handles, platform limitations, or children that deliberately escape the
parent tree can require caller-side containment. Treat iCLI cleanup as the runtime-owned best effort inside the JDK
process tree model, not as an OS sandbox.

## Not in 0.1.0

- Raw session pooling.
- Stateful affinity pools.
- Generic/core async request API.
- A real MCP SDK adapter.
- Backend-specific process library APIs in core.
- Machine-dependent performance promises.

The broader scope boundary is described in [Non-goals](../explanations/non-goals.md).

The cleanup boundary is explained in [Process Cleanup Limits](../explanations/process-cleanup-limits.md).
