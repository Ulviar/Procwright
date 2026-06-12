# Changelog

All notable changes to Procwright are documented in this file. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project adheres to
[Semantic Versioning](https://semver.org/spec/v2.0.0.html) as defined in the project versioning policy.

## [Unreleased]

### Added

- `Expect.expectTextMatch(...)` / `expectRegexMatch(...)` returning `ExpectMatch` with the matched text, regex
  capture groups, and the output consumed before the match.
- `CapturePolicy.toPath(...)` (redirect run output to files) and `CapturePolicy.discard()` for one-shot runs.
- `CommandInput.fromPath(Path)` — stdin streamed from a file without buffering in memory.
- `RunOptions` wither methods (`withTimeout`, `withCapture`, `withShutdown`, `withCharsetPolicy`, `withOutputMode`).
- `CommandService.forCommand(CommandSpec)` and a `CommandResult` constructor overload with an explicit charset.
- Kotlin: suspend `requestAwait` extensions for `ProtocolSession`, `PooledLineSession`, and `PooledProtocolSession`
  (with `kotlin.time.Duration` overloads).
- Integrations: `JsonCodec.toJackson(...)` / `fromJackson(...)` bridges between `JsonValue` and Jackson `JsonNode`.
- `package-info.java` for every exported package and usage examples in entry-point Javadoc; documented default
  values on every options `defaults()` method and in the reference documentation.
- Standard repository hygiene: contributing guide, security policy, code of conduct, changelog, issue and pull
  request templates, Dependabot configuration, and a release-triggered documentation deployment workflow.
- CI hardening: Gradle build caching and wrapper validation, job timeouts, concurrency cancellation for pull
  requests, a strict-mode documentation build job, and a non-blocking compile job for the comparison research module.
- SPDX license headers across all sources, enforced by Spotless.

### Changed

- `Duration.ZERO` now uniformly means "disabled" for the run timeout (previously it killed the process instantly).
- Protocol sessions: unread stderr no longer kills the session — stderr is retained in a bounded drop-oldest buffer;
  stdout backlog overflow remains a strict typed failure. `ProtocolSessionOptions` keeps a single
  `outputBacklogLimit` (bytes); `LineSessionOptions.stdoutBacklogLimit` renamed to `stdoutBacklogLines` (lines).
- Line and protocol sessions report `PROCESS_EXITED` when the process died before a write, and follow-up requests
  after a terminal failure surface the original failure reason instead of a generic `CLOSED`.
- Line length limits (`maxLineChars` and protocol `readLine`) apply to line content excluding the LF/CRLF terminator.
- Diagnostics delivery is now ordered per recipient within a run (still asynchronous and best-effort).
- `Session.closeStdin()` signals closure immediately and never blocks behind a stuck stdin write.
- `Expect` follows the session charset by default; an explicitly configured `ExpectOptions` charset takes precedence.
- `apiCompatibilityCheck` is part of `quickCheck` (tier 0), matching the documented test-tier model.

### Fixed

- Deadlock risk in `closeStdin()` when a concurrent write was blocked on a full stdin pipe.
- Off-by-one rejection of lines of exactly the maximum length terminated by CRLF (line sessions and protocol
  `readLine`).
- Orphaned descendant processes holding the output pipe after the parent exited are now force-stopped from a
  liveness snapshot, and the run failure message explains the likely cause.
- Dependency verification metadata for the Jackson 2.21.3 upgrade; CI on `main` is green again.
- Windows CI runner label moved off the temporary `windows-2025-vs2026` image back to `windows-2025`.

## [0.1.0] — unreleased baseline

Initial public baseline: scenario-first API (`run`, `interactive`, `lineSession`, `protocolSession`, `listen`,
pooled session variants, `expect`), bounded capture and transcripts, timeout supervision with process-tree cleanup,
PTY capability boundary, diagnostics, optional Kotlin ergonomics module, and optional CLI-backed integrations module.
Not yet published to Maven Central.
