# Known Limitations

This page lists user-visible limitations in `0.1.0`.

## Pre-1.0 API

The API is public but still pre-1.0. Names, package structure, and option shapes may change before `1.0.0`.

## Terminal support

Terminal capability is behind `TerminalPolicy` and `PtyProvider`. The current system provider is platform-dependent.
Windows ConPTY support is not shipped in `0.1.0`.

Terminal-required scenarios should fail explicitly when no provider is available. Silent fallback to ordinary pipes is
not part of the contract.

## Kotlin reference

The optional Kotlin module is documented in [Kotlin API](../reference/kotlin-api.md). There is no separate Kotlin API
site in `0.1.0`.

## Java runtime differences

The public artifacts target Java 17 and run on Java 17 or newer. On Java 21 and newer runtimes, Procwright may use virtual
threads internally; Java 17 uses a platform-thread fallback for internal background work. High-concurrency performance
can therefore differ by runtime.

## Process cleanup limits

One-shot timeout cleanup uses process-tree cleanup through JDK `ProcessHandle` descendant tracking. Session close and
idle-timeout paths also shut down their owned process. This cleanup is not universal containment for every process
topology.

Detached descendants, inaccessible process handles, platform limitations, or children that deliberately escape the
parent tree can require caller-side containment. Treat Procwright cleanup as the runtime-owned best effort inside the JDK
process tree model, not as an OS sandbox.

## Not in 0.1.0

- Pooling is available for line sessions and typed protocol sessions, not for raw interactive sessions.
- Worker pools do not pin a specific caller to a specific worker.
- Core request/session APIs are synchronous.
- Core APIs do not expose backend-specific process library types.
- Performance depends on the command, OS, Java runtime, and workload; `0.1.0` does not publish performance guarantees.

The broader scope boundary is described in [Non-goals](../explanations/non-goals.md).

The cleanup boundary is explained in [Process Cleanup Limits](../explanations/process-cleanup-limits.md).
