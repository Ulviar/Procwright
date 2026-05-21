# Current Pre-release Status

This page describes the current unreleased baseline. Replace it with versioned release notes when a public release
candidate is cut.

## Status

- Release type: unreleased pre-release candidate baseline.
- Version: `0.0.0-SNAPSHOT`.
- Java release variants: 17, 21, and 25 from one source tree; default local development target is 25.
- Public artifact target: Java 17.
- API stability: pre-1.0; first-RC scenario call shapes are being frozen for `run`, `interactive`, `expect`,
  `lineSession`, `protocolSession`, `listen`, `pooled`, and `pooledProtocol`.

## Current Branch Behavior

- Scenario-first command service with `run`, `interactive`, `lineSession`, `listen`, and `pooled` workflows.
- `Expect` prompt automation over iCLI-owned sessions.
- Bounded one-shot capture with typed command results.
- Timeout and shutdown handling with process-tree cleanup tests.
- Raw interactive sessions with guarded stdin, explicit output ownership, idempotent close, and exit futures.
- Line-oriented request/response sessions with bounded transcripts, custom decoders, timeout/EOF distinction, and
  serialized requests.
- Generic typed protocol sessions with adapter-owned framing, deadline-aware readers/writers, readiness probes, strict
  charset decoding, request/response size limits, and bounded transcripts.
- Pooled typed protocol sessions with warmup, min-idle replenishment, health/reset hooks, retirement reasons, and richer
  metrics.
- Listen-only streaming sessions with output listeners, bounded diagnostics, close semantics, and listener failure
  propagation.
- PTY capability boundary through `TerminalPolicy` and `PtyProvider`.
- Structured diagnostics with redaction-friendly command echo.
- Optional Kotlin ergonomics module.
- Optional CLI-backed integrations module with JSON, JSON Lines, Content-Length framing, protocol adapters, cancellable
  calls, adapter errors, and command-backed tool wrappers.
- Bounded stress suite and comparison research module.
- Public MkDocs documentation and generated Java API docs for core and integrations.

## Stabilization Decisions

- `CommandService` remains the main public entry point for the first release-candidate baseline.
- Convenience one-line shortcut APIs are not added before the first release candidate.
- `SessionOptions.idleTimeout` keeps its current name and caller-visible activity semantics.
- The current `ScenarioPresets` set is frozen for the first release-candidate baseline.
- `protocolSession` and `pooledProtocol` are canonical scenario APIs, not low-level flag bundles.
- Session-family handles are sealed public contracts backed by hidden iCLI implementations.
- Diagnostics remains best-effort and unordered.
- Core, integrations, and Kotlin public API type sets are guarded by exact baseline tests.
- Java 17/21/25 release variants are selected with `icli.javaRelease` and checked in CI.

## Known Limitations

- No stable Maven Central artifact is published yet. GitHub Packages metadata is configured, but no public release
  artifact has been cut.
- Windows ConPTY support is not shipped in the current baseline.
- Java 17 uses platform-thread fallback for internal background work; Java 21+ may use virtual threads internally.
- Generated Kotlin API docs are not part of the public site yet; Kotlin public declarations are checked through KDoc in
  source.
- Raw session pooling, stateful affinity pools, generic/core async request API, and a real MCP SDK adapter are not
  included.
- Machine-dependent benchmark results are research data, not performance guarantees.
- Documentation toolchain transitive Python dependencies are not hash-pinned yet. They are isolated from runtime
  artifacts, but this remains a documentation-build supply-chain item until a lock or hash workflow is added.

## Verification

Use the release-candidate gate before treating a branch as releasable:

```bash
./gradlew releaseCandidateCheck
```

`releaseCandidateCheck` requires a clean worktree. During active documentation or code edits, use the narrower gates
listed in [Compatibility](compatibility.md) and [Installation](installation.md).
