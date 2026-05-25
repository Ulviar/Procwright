# API Baseline

iCLI treats public API as the exported, documented scenario surface, not as every implementation class in the repository.

## Java core

The core artifact is the named Java module `io.github.ulviar.icli`. It exports only these packages:

- `io.github.ulviar.icli`
- `io.github.ulviar.icli.command`
- `io.github.ulviar.icli.diagnostics`
- `io.github.ulviar.icli.preset`
- `io.github.ulviar.icli.session`
- `io.github.ulviar.icli.terminal`

The `0.1.0` public Java API is the set of exported packages and types documented by the generated Java API docs. Future
pre-1.0 releases may change that API. Public call-shape changes will be documented with updated examples.

The `0.1.0` scenario baseline covers `run`, `interactive`, `lineSession`, `protocolSession`, `lineSession().pooled()`,
and `protocolSession(factory).pooled()`. Generic/core async request orchestration and raw session pooling are outside
this baseline; the narrow cancellable JSON Lines helper remains part of the optional integrations module.

## Optional modules

The integrations artifact exports only `io.github.ulviar.icli.integration` and requires the core module transitively for
JPMS consumers.

The Kotlin artifact publishes `io.github.ulviar.icli.kotlin` extensions over the Java core.

## Compatibility rule

Before `1.0.0`, iCLI may still make breaking API changes. Breaking changes will be documented in public docs, examples,
and the compatibility policy. Internal packages are not compatibility surface.
