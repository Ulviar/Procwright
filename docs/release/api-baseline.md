# API Baseline

iCLI treats public API as the exported, documented scenario surface, not as every implementation class in the repository.

## Java core

The core artifact is the named Java module `com.github.ulviar.icli`. It exports only these packages:

- `com.github.ulviar.icli`
- `com.github.ulviar.icli.command`
- `com.github.ulviar.icli.diagnostics`
- `com.github.ulviar.icli.preset`
- `com.github.ulviar.icli.session`
- `com.github.ulviar.icli.terminal`

The exact public type set is guarded by `PublicApiSurfaceTest`. New public types require an intentional API baseline
change, documentation updates, and compile-tested examples when user call shape changes.

The first release-candidate scenario freeze covers `run`, `interactive`, `lineSession`, `protocolSession`,
`lineSession().pooled()`, and `protocolSession(factory).pooled()`. Generic/core async request orchestration and raw
session pooling are outside this baseline; the narrow cancellable JSON Lines helper remains part of the optional
integrations module.

## Optional modules

The integrations artifact exports only `com.github.ulviar.icli.integration`, requires the core module transitively for
JPMS consumers, and is guarded by `PublicIntegrationApiSurfaceTest`.

The Kotlin artifact publishes only `com.github.ulviar.icli.kotlin` and is guarded by `PublicKotlinApiSurfaceTest`.

## Compatibility rule

Before `1.0.0`, iCLI may still make breaking API changes, but they must be explicit: update the baseline guard, public
docs, examples, and release notes together. Internal packages are not compatibility surface.
