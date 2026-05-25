# Reference

Use reference pages when you need public contracts, policies, and release-relevant guarantees rather than a task guide.

Current reference entry points:

- [Scenario contracts](../scenarios/index.md)
- [Command model](command-model.md)
- [Policies](policies.md)
- [Output ownership](output-ownership.md)
- [Portable command construction](portable-command-construction.md)
- [Results and errors](results-and-errors.md)
- [Diagnostics](diagnostics.md)
- [Security](security.md)
- [Platforms and PTY](platforms-and-pty.md)
- [Kotlin API](kotlin-api.md)
- [Generated API docs](../api/index.md)

## Local API docs

Build Java API docs locally:

```bash
./gradlew publicJavaJavadocCheck
```

Kotlin usage and public extensions are documented in [Kotlin API](kotlin-api.md). Kotlin public declarations are also
checked for KDoc during module verification:

```bash
./gradlew :icli-kotlin:check
```
