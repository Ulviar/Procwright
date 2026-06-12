# Contributing to Procwright

Procwright favors a small, reliable core over broad surface area. Changes are expected to come with tests and
documentation, not just code.

## Toolchain

- **JDK.** The build compiles with `--release` per the `procwright.javaRelease` property (default `25`; supported `17`,
  `21`, `25`). Your JDK must be at least as new as the chosen target. On an older JDK pass the target explicitly:

  ```bash
  ./gradlew quickCheck --project-prop=procwright.javaRelease=17
  ```

- **uv** — only needed for `publicDocsCheck`, which builds the MkDocs site in an isolated environment.
- **git.**

The Gradle wrapper handles everything else. No other setup is required.

## Build and verification tiers

```bash
./gradlew quickCheck             # fast unit/contract tier
./gradlew scenarioCheck          # scenario-level integration across core, Kotlin, and integrations modules
./gradlew regressionCheck        # bounded stress + public boundary regression checks
./gradlew releaseCandidateCheck  # complete local release gate
```

`quickCheck` is the inner loop. Run `scenarioCheck` before opening a PR that touches behavior, and `regressionCheck`
when the change affects process lifecycle, streams, or module boundaries. `releaseCandidateCheck` additionally requires
a clean worktree (including untracked files) and `uv`.

Formatting is enforced; repair with:

```bash
./gradlew spotlessApply
```

## Dependency changes

Every dependency bump must regenerate the Gradle dependency verification metadata:

```bash
./gradlew --write-verification-metadata sha256 check --refresh-dependencies
```

Without `--refresh-dependencies`, a warm local cache can hide missing parent-POM entries that only fail later on CI's
cold cache.

## Conventions

- Code, comments, Javadoc/KDoc, tests, and commit messages are written in English. Maintainer context documents in
  `context/` are in Russian.
- Architecture decisions get an ADR in `context/decisions/`.
- Behavior is fixed by a test first, then implemented. The mandatory quality standard is
  [context/quality/engineering-charter.md](context/quality/engineering-charter.md).
- Public-API changes must keep `./gradlew apiCompatibilityCheck` green. If the change is intentional, update the
  baseline in `config/api-compatibility/` deliberately via `./gradlew writeApiCompatibilityBaseline` and say why in the
  PR.

## Pull requests

- Keep changes small and focused on one concern.
- Update tests and documentation together with behavior. A PR that changes behavior without both is not done.
