# Known limitations

- The API is pre-1.0 and may change between releases.
- Pooling is available for line and factory-backed protocol sessions, not raw interactive sessions.
- Pools do not provide caller-to-worker affinity.
- Core request/session APIs are synchronous. The optional Kotlin module adds cancellable coroutine terminals, but no
  suspending resource-opening terminal.
- Windows ConPTY support is not shipped in planned `0.1.0`.
- Process cleanup is best effort within the process tree visible to JDK `ProcessHandle`; detached or inaccessible
  descendants can survive.
- Performance depends on the command, operating system, Java runtime, and workload; no fixed performance guarantee is
  published.

See [process cleanup limits](../explanations/process-cleanup-limits.md) before running untrusted or self-daemonizing
processes.
