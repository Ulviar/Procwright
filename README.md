# iCLI

iCLI is being rewritten as a JVM library for safe, scenario-first control of external command-line processes.

This branch currently contains the foundation for the rewrite, a one-shot execution kernel, a raw interactive session
scenario, a line-oriented request/response workflow, a small expect automation helper, initial PTY transport support for
terminal-required interactive sessions, a listen-only streaming scenario, pooled line-session workers, typed scenario
presets for common workflows, an optional CLI-backed integrations module, and a bounded stress suite. The public API and
runtime are still incomplete: documentation must not promise behavior before tests and implementation prove it.

Project context is maintained in Russian under [context/](context/). Code, public APIs, Javadocs, tests, and commit
messages are written in English.

## Current status

- Clean rewrite branch.
- Gradle Java project foundation.
- Project version: `0.0.0-SNAPSHOT`.
- Java 25 bytecode target.
- JDK 25 or newer is required to build the project.
- Compile-tested API sketches for the first public surface.
- Deterministic process fixture for success, stderr, large output, and timeout cases.
- One-shot `run` scenario with direct argv, explicit shell mode, bounded stdout/stderr capture, timeout supervision,
  working directory, environment overrides, charset decoding, stdin input, and merged stderr support.
- Internal scenario profile resolver for turning scenario defaults and per-call overrides into a validated execution
  plan.
- Raw `interactive` session scenario with guarded stdin, raw stdout/stderr streams, `send`, `sendLine`, `closeStdin`,
  `onExit`, idempotent `close`, and caller-visible idle-timeout shutdown.
- Line-oriented `lineSession` scenario with serialized requests, default and custom response decoders, bounded
  transcripts, per-request timeouts, EOF distinction, and stderr draining.
- `Expect` helper over `Session` with literal and regex matching, send/sendLine, bounded transcripts, timeout/EOF
  distinction, and optional ANSI/control-sequence filtering.
- Terminal policy for session scenarios: `DISABLED`, `AUTO`, and `REQUIRED`.
- PTY provider SPI with an initial Unix `script(1)` system provider, explicit unavailable behavior, terminal size
  request handling, and terminal control signal helpers.
- Listen-only `listen` scenario with synchronous output listeners, bounded diagnostics, default stdin close, optional
  deferred stdin close, timeout, listener-failure propagation, and stdout/stderr draining.
- Diagnostics hooks with structured lifecycle/timeout/truncation events, lifecycle `runId`, redaction-friendly command
  echo, and optional transcript sinks. Diagnostic failures do not change command behavior.
- Optional `:icli-kotlin` module with Kotlin receiver-style extensions, suspending wrappers, and Flow adapters. The Java
  core artifact remains Kotlin-free.
- Pooled `lineSession` workers through `CommandService.pooled(...)`, with max/warmup sizing, acquire timeout,
  reset/health hooks, worker retirement, graceful drain, and metrics snapshots.
- `ScenarioPresets` typed builder customizers for command automation, environment diagnostics, REPL line mode, prompt
  automation sessions, log following, binary byte snapshots, terminal-required sessions, and warm worker pools.
- Optional `:icli-integrations` module with a minimal JSON model/codec, JSON Lines helpers, `JsonLineSession`,
  Content-Length framed JSON helpers for MCP-like stdin/stdout protocols, cancellable call handles, structured adapter
  errors, and command-backed tool result wrappers. CLI output is treated as untrusted data, not instructions.
- Bounded `stressTest` suite wired into `check`, covering large stdout/stderr retention, timeout churn, rapid session
  open/close, pooled contention, and conditional PTY stability.
- Apache-2.0 license, release policies, dependency review, migration notes, release checklist, unified module
  coordinates, Javadoc artifacts for Java modules, Kotlin API KDoc checks, and GitHub Actions CI for Linux, macOS, and
  Windows.

See [context/quality/engineering-charter.md](context/quality/engineering-charter.md) for the quality standard.

## Modules

- `:` is the Java core module with no runtime dependencies outside the JDK.
- `:icli-kotlin` is an optional Kotlin ergonomics module with receiver-style extensions, suspending wrappers, and Flow
  adapters.
- `:icli-integrations` is an optional Java module for CLI-backed integration helpers. It does not depend on an MCP SDK.
- `:icli-comparison` is a research/evaluation module for comparing external process libraries against iCLI scenarios.
  It is not a runtime dependency of the core artifact.

## Verification

```bash
./gradlew check
./gradlew javadoc
./gradlew :icli-comparison:comparisonReport
```

`check` runs unit tests, integration tests, module tests, and the bounded stress suite.

## Release status

This rewrite is not published as a public release yet. Release policies and checklists live in
[context/release/](context/release/), and the current version remains `0.0.0-SNAPSHOT`.

## License

iCLI is licensed under the Apache License, Version 2.0. See [LICENSE](LICENSE).
