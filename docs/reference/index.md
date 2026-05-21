# Reference

Reference pages describe public contracts, policies, and release-relevant guarantees. They should stay narrower than
the scenario guides and should avoid tutorial prose.

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
- [Generated API docs](../api/index.md)

## Local API docs

Build Java API docs locally:

```bash
./gradlew publicJavaJavadocCheck
```

Kotlin public declarations are checked for KDoc during module verification:

```bash
./gradlew :icli-kotlin:check
```
