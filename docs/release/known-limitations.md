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

No Maven Central artifact is published from this branch yet. GitHub Packages metadata, signing hooks, Java 17
publication guards, and SemVer/non-SNAPSHOT remote publish guards are configured, but no stable public version has been
cut.

## Java release variants

Java 17, 21, and 25 variants build from the same source tree. The Java 17 variant uses platform-thread fallback for
internal background work, so high-concurrency performance may differ from Java 21/25 runtimes that can use virtual
threads.

## Process cleanup limits

One-shot timeout cleanup is covered by the release gate, including process-tree cleanup through JDK `ProcessHandle`
descendant tracking. Session close and idle-timeout paths also shut down their owned process, but the current release
state should not be read as a universal containment guarantee for every process topology.

Detached descendants, inaccessible process handles, platform limitations, or children that deliberately escape the
parent tree can require caller-side containment. Treat iCLI cleanup as the runtime-owned best effort inside the JDK
process tree model, not as an OS sandbox.

## Not in the current MVP

- Raw session pooling.
- Stateful affinity pools.
- Generic/core async request API.
- A real MCP SDK adapter.
- Backend-specific process library APIs in core.
- Machine-dependent performance promises.

The broader scope boundary is described in [Non-goals](../explanations/non-goals.md).

The cleanup boundary is explained in [Process Cleanup Limits](../explanations/process-cleanup-limits.md).

## Documentation scope

Public docs describe implemented and tested behavior only. Internal `context/` documents may discuss future plans that
are not public guarantees.
