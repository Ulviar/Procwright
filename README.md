# iCLI

iCLI is being rewritten as a JVM library for safe, scenario-first control of external command-line processes.

This branch currently contains the foundation for the rewrite, the first one-shot execution kernel, and a raw
interactive session scenario. The public API and runtime are still incomplete: documentation must not promise behavior
before tests and implementation prove it.

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

See [context/quality/engineering-charter.md](context/quality/engineering-charter.md) for the quality standard.
