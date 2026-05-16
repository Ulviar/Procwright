# iCLI

iCLI is being rewritten as a JVM library for safe, scenario-first control of external command-line processes.

This branch currently contains the foundation for the rewrite. The public API and runtime are intentionally incomplete:
documentation must not promise behavior before tests and implementation prove it.

Project context is maintained in Russian under [context/](context/). Code, public APIs, Javadocs, tests, and commit
messages are written in English.

## Current status

- Clean rewrite branch.
- Gradle Java project foundation.
- Java 21 bytecode target.
- Compile-tested API sketches for the first public surface.
- Deterministic process fixture for success, stderr, large output, and timeout cases.

See [context/quality/engineering-charter.md](context/quality/engineering-charter.md) for the quality standard.
