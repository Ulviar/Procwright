# Релизный checklist

Этот список применяется к первому OSS release candidate и каждому последующему релизу.

## Перед freeze

- README описывает только реализованное и протестированное поведение.
- `context/quality/scorecard.md` не содержит устаревшего статуса по уже завершенным фазам.
- Все ADR для публичных архитектурных решений добавлены в `context/decisions/`.
- Migration notes отражают переносимые идеи старой версии и то, что намеренно не переносится.
- Versioning и compatibility policies актуальны для текущего Java/Kotlin baseline.
- Dependency review не содержит неизвестных runtime dependencies.
- Gradle wrapper distribution checksum и dependency verification metadata актуальны после каждого изменения
  build/test dependencies.
- Если documentation toolchain остается без lock/hash-pinned transitive dependencies, этот residual supply-chain risk
  явно принят в release notes; предпочтительный вариант перед release — добавить lock/hashes workflow.

## Локальные проверки

Обязательный набор:

```bash
./gradlew spotlessCheck
./gradlew quickCheck
./gradlew scenarioCheck
./gradlew regressionCheck
./gradlew check --rerun-tasks
./gradlew javadoc --rerun-tasks
./gradlew publicDocsCheck
./gradlew :icli-comparison:comparisonCheck
./gradlew releaseCandidateCheck
git diff --check
git diff --exit-code
```

`check` включает unit, integration, module tests, Kotlin public API KDoc check, bounded stress suite, non-mutating
comparison regression gate и JMH benchmark compilation/metadata generation. `jmhBenchmarkSmoke`, `jmhBenchmark` и
`jmhPtyBenchmark` остаются исследовательскими/manual задачами и не являются performance pass/fail gate.

Назначение уровней описано в [../evals/test-tiers.md](../evals/test-tiers.md). `releaseCandidateCheck` является
локальным составным gate и требует clean worktree, включая untracked files.

Если `releaseCandidateCheck` недоступен в окружении, clean worktree проверяется эквивалентом
`git status --porcelain=v1 --untracked-files=all`: вывод должен быть пустым.

`spotlessApply` допустим как repair-команда до release gate, но не как сама проверка.

Сценарный release gate:

- новое API расширяет один из канонических сценариев (`run`, `lineSession`, `interactive`, `expect`, `listen`,
  `pooled`) или optional integration layer;
- новые/измененные public entry points, examples и tests сверены с
  [../scenario-contracts.md](../scenario-contracts.md);
- новые/измененные cookbook recipes сверены с [../scenario-cookbook.md](../scenario-cookbook.md) и compile-tested
  examples;
- публичные docs в `docs/` собираются через `publicDocsCheck` и не обещают behavior без tests/examples;
- dependency-specific types не протекают в core public API;
- external process-library dependencies из comparison не протекают в публичные artifacts;
- terminal/PTY возможности остаются capability/transport boundary;
- terminal methods остаются только в session-family API, а `run`/`listen` не получают PTY knobs без нового ADR;
- diagnostics event schema и redaction contract остаются согласованными с [../diagnostics.md](../diagnostics.md);
- comparison qualitative assessment и ADR остаются согласованными с публичной моделью;
- security-sensitive invariants имеют regression tests: process-tree shutdown, clean environment, bounded line length,
  expect transcript redaction, JSON depth limit.

## CI

GitHub Actions workflow должен запускать `./gradlew check` и `./gradlew javadoc` и пройти на:

- Linux;
- macOS;
- Windows.

POSIX-only и PTY-only tests должны skip-аться через assumptions, а не падать из-за недоступной платформенной
возможности.

Workflow permissions должны оставаться минимальными, а external actions должны быть pinned to commit SHA.

## Перед публикацией

- Версия больше не `0.0.0-SNAPSHOT`.
- Release notes перечисляют shipped behavior, breaking changes и known limitations.
- Source и Javadoc artifacts собираются для Java modules.
- Public MkDocs site собирается в strict mode и включает generated Java API docs.
- Kotlin public API задокументирован через KDoc в sources artifact и проверяется `:icli-kotlin:kotlinApiDocsCheck`.
- License file присутствует в корне репозитория.
- Если добавляется Maven Central publishing, signing и metadata оформляются отдельным ADR.
