## Summary

<!-- What does this change and why? -->

## Checklist

- [ ] `./gradlew quickCheck` passes locally (or `./gradlew scenarioCheck` for behavior changes).
- [ ] Docs are updated if observable behavior changed.
- [ ] Code and comments are in English (see AGENTS.md).
- [ ] Dependency bumps regenerate `gradle/verification-metadata.xml` via `./gradlew --write-verification-metadata sha256 check --refresh-dependencies`.
