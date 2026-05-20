# ADR-0017: Стратегия публикации release artifacts

## Статус

Accepted, updated after publishing setup implementation.

## Контекст

Проект подготовил release hardening baseline: group/version, source/Javadoc artifacts для Java modules, public docs,
license, dependency review и release checklist. Для использования iCLI как внешней зависимости нужен реальный publishing
setup, но credentials и signing material не должны попадать в репозиторий.

## Решение

Release setup публикует Java 17-targeted artifacts с координатами:

- `com.github.ulviar:icli`;
- `com.github.ulviar:icli-kotlin`;
- `com.github.ulviar:icli-integrations`.

Текущий configured target — GitHub Packages:

- `maven-publish` и `signing` включены для public artifacts;
- POM metadata содержит name, description, URL, Apache-2.0 license, SCM и developers;
- credentials читаются только из `GITHUB_ACTOR`/`GITHUB_TOKEN`;
- signing key читается только из `SIGNING_KEY`/`SIGNING_PASSWORD`;
- publish tasks fail fast, если `icli.javaRelease != 17`.
- remote publish tasks fail fast, если version остается `*-SNAPSHOT` или не похожа на SemVer; release job передает
  version из GitHub release tag через `icli.version`.

Maven Central остается future release-infrastructure step. Он не должен менять public API или process runtime.

## Инварианты

- Publishing setup не должен добавлять runtime dependency в public artifacts.
- Credentials не хранятся в репозитории.
- Published artifacts собираются с Java 17 target; Java 21/25 остаются checked source variants.
- Published artifacts должны соответствовать JPMS/package boundary tests.
- Source и Javadoc artifacts обязательны для Java modules.
- Kotlin artifact остается optional и не становится dependency core module.

## Последствия

Плюсы:

- iCLI можно готовить как внешнюю dependency с устойчивыми coordinates.
- Artifact policy явно фиксирует Java 17 minimum target.
- GitHub Packages publication не требует хранения secrets в repo.
- Maven Central задача остается отдельным reviewable change.

Минусы:

- Первый RC пока нельзя установить из Maven Central.
- GitHub Packages artifact появится только после tagged GitHub release и успешного release job с repository
  permissions/secrets.
