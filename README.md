# iCLI

iCLI is being rewritten as a JVM library for safe, scenario-first control of external command-line processes.

This branch currently contains the foundation for the rewrite, the first one-shot execution kernel, a raw interactive
session scenario, the first line-oriented request/response workflow, a small expect automation helper, and initial PTY
transport support for terminal-required interactive sessions, a listen-only streaming scenario, and pooled line-session
workers, plus typed scenario presets for common workflows. The public API and runtime are still incomplete:
documentation must not promise behavior before tests and implementation prove it.

Project context is maintained in Russian under [context/](context/). Code, public APIs, Javadocs, tests, and commit
messages are written in English.

## Current status

- Clean rewrite branch.
- Gradle Java project foundation.
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

See [context/quality/engineering-charter.md](context/quality/engineering-charter.md) for the quality standard.
