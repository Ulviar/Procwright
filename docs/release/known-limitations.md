# Known Limitations

This page tracks release-relevant limitations for `0.1.0`.

## Pre-1.0 API

The API is public but still pre-1.0. Names, package structure, and option shapes may change before `1.0.0`.

## Terminal support

Terminal capability is behind `TerminalPolicy` and `PtyProvider`. The current system provider is platform-dependent.
Windows ConPTY support is not shipped in `0.1.0`.

Terminal-required scenarios should fail explicitly when no provider is available. Silent fallback to ordinary pipes is
not part of the contract.

## Kotlin generated docs

The optional Kotlin module is documented in [Kotlin API](../reference/kotlin-api.md). A separate generated Dokka site is
not published for `0.1.0`.

## Java runtime differences

The public artifacts target Java 17 and run on Java 17 or newer. On Java 21 and newer runtimes, iCLI may use virtual
threads internally; Java 17 uses a platform-thread fallback for internal background work. High-concurrency performance
can therefore differ by runtime.

## Process cleanup limits

One-shot timeout cleanup uses process-tree cleanup through JDK `ProcessHandle` descendant tracking. Session close and
idle-timeout paths also shut down their owned process. This cleanup is not universal containment for every process
topology.

Detached descendants, inaccessible process handles, platform limitations, or children that deliberately escape the
parent tree can require caller-side containment. Treat iCLI cleanup as the runtime-owned best effort inside the JDK
process tree model, not as an OS sandbox.

## Not in 0.1.0

- Raw session pooling.
- Stateful affinity pools.
- Generic/core async request API.
- Backend-specific process library APIs in core.
- Machine-dependent performance promises.

The broader scope boundary is described in [Non-goals](../explanations/non-goals.md).

The cleanup boundary is explained in [Process Cleanup Limits](../explanations/process-cleanup-limits.md).
