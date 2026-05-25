# Релизный checklist

Этот список применяется к публичным релизам iCLI.

## Перед freeze

- README описывает только реализованное и протестированное поведение.
- `context/quality/scorecard.md` не содержит устаревшего статуса по уже завершенным фазам.
- Все ADR для публичных архитектурных решений добавлены в `context/decisions/`.
- Release docs не создают фиктивную миграцию для первого публичного релиза без пользовательской базы.
- Versioning и compatibility policies актуальны для текущего Java/Kotlin baseline.
- Приняты стабилизационные решения по public API, PTY/platform strategy, publishing strategy и Kotlin generated docs.
- Approved public API surface зафиксирован в [public-api-baseline.md](public-api-baseline.md) и проверяется exact baseline
  tests для core, integrations и Kotlin modules.
- Dependency review не содержит неизвестных runtime dependencies.
- Gradle wrapper distribution checksum и dependency verification metadata актуальны после каждого изменения
  build/test dependencies.
- Если documentation toolchain остается без lock/hash-pinned transitive dependencies, этот residual supply-chain risk
  явно принят в public release docs; предпочтительный вариант перед release — добавить lock/hashes workflow.
- Publishing/signing setup реализован по ADR-0017; remote publish запрещает `*-SNAPSHOT` и non-SemVer version, а
  публичный artifact считается готовым к публикации только после Java 17-targeted local publication check и CI job с
  repository secrets.

## Локальные проверки

Обязательный набор:

```bash
./gradlew spotlessCheck
./gradlew quickCheck
./gradlew scenarioCheck
./gradlew regressionCheck
./gradlew check --rerun-tasks
./gradlew publicJavaJavadocCheck --rerun-tasks
./gradlew publicDocsCheck
./gradlew releaseCandidateCheck
git diff --check
git diff --exit-code
```

`check` включает unit, integration, module tests, Kotlin public API KDoc check и bounded stress suite. Comparison/JMH
tasks остаются исследовательскими/manual задачами и не являются release pass/fail gate.

Назначение уровней описано в [../evals/test-tiers.md](../evals/test-tiers.md). `releaseCandidateCheck` является
локальным составным gate и требует clean worktree, включая untracked files.

Если `releaseCandidateCheck` недоступен в окружении, clean worktree проверяется эквивалентом
`git status --porcelain=v1 --untracked-files=all`: вывод должен быть пустым.

`spotlessApply` допустим как repair-команда до release gate, но не как сама проверка.

Сценарный release gate:

- новое API расширяет один из канонических сценариев (`run`, `lineSession`, `protocolSession`, `interactive`, `expect`,
  `listen`, `lineSession().pooled()`, `protocolSession(factory).pooled()`) или optional integration layer;
- новое/измененное API обновляет exact public API baseline test соответствующего модуля;
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
- security-sensitive invariants имеют regression tests: process-tree shutdown, clean environment, bounded line length,
  expect transcript redaction, JSON depth limit.

## CI

GitHub Actions workflow должен запускать `./gradlew check` и `./gradlew publicJavaJavadocCheck` и пройти на:

- Linux;
- macOS;
- Windows.

POSIX-only и PTY-only tests должны skip-аться через assumptions, а не падать из-за недоступной платформенной
возможности.

Workflow permissions должны оставаться минимальными, а external actions должны быть pinned to commit SHA.

## Перед публикацией

- Версия больше не `0.0.0-SNAPSHOT`.
- Public release docs перечисляют shipped behavior, known limitations и только реальные breaking changes для
  опубликованных API, если такие появятся.
- Source и Javadoc artifacts собираются для Java modules.
- Public MkDocs site собирается в strict mode и включает generated Java API docs.
- Kotlin public API задокументирован через KDoc в sources artifact и проверяется `:icli-kotlin:kotlinApiDocsCheck`.
- Generated Kotlin API docs через Dokka не являются gate `0.1.x`, пока ADR-0019 остается действующим решением.
- License file присутствует в корне репозитория.
- POM metadata соответствует Apache-2.0 license, SCM и planned coordinates.
- Release job передает non-SNAPSHOT version через `icli.version` из GitHub release tag.
- Local publication check проходит:

```bash
./gradlew publishToMavenLocal --project-prop=icli.javaRelease=17
```

- Maven Central publishing остается отдельным release-infrastructure step; GitHub Packages является текущим configured
  external artifact target.

## Cut Release через GitHub Release

1. Выбрать SemVer tag, например `v0.1.0`.
2. На clean `main` прогнать `./gradlew releaseCandidateCheck --project-prop=icli.javaRelease=17`.
3. Убедиться, что CI на `main` зеленый для Java 17/21/25 на Linux, macOS и Windows.
4. Создать GitHub Release из выбранного tag и именно опубликовать его, а не оставить draft-only release.
5. Проверить, что release workflow передал version без ведущего `v`, запустил Java 17-targeted publish и не использовал
   `*-SNAPSHOT`.
6. Проверить, что repository permissions/secrets доступны: `GITHUB_TOKEN` с `packages: write`; signing secrets
   `SIGNING_KEY`/`SIGNING_PASSWORD`, если release должен быть signed.
7. После успешного job проверить, что GitHub Packages содержит `com.github.ulviar:icli`,
   `com.github.ulviar:icli-integrations` и `com.github.ulviar:icli-kotlin` с выбранной версией.
