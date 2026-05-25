# ADR-0017: Стратегия публикации release artifacts

## Статус

Accepted, updated after publishing setup implementation.

## Контекст

Проект подготовил release hardening baseline: group/version, source/Javadoc artifacts для Java modules, public docs,
license, dependency review и release checklist. Для использования iCLI как внешней зависимости нужен реальный publishing
setup, но credentials и signing material не должны попадать в репозиторий.

## Решение

Release setup публикует Java 17-targeted artifacts с координатами:

- `io.github.ulviar:icli`;
- `io.github.ulviar:icli-kotlin`;
- `io.github.ulviar:icli-integrations`.

Текущий configured target — Maven Central через Central Portal:

- `maven-publish` и `signing` включены для public artifacts;
- POM metadata содержит name, description, URL, Apache-2.0 license, SCM и developers;
- Central Portal credentials читаются только из `CENTRAL_USERNAME`/`CENTRAL_PASSWORD`;
- signing key читается только из `SIGNING_KEY`/`SIGNING_PASSWORD` и обязателен для Central bundle;
- publish tasks fail fast, если `icli.javaRelease != 17`.
- remote publish tasks fail fast, если version остается `*-SNAPSHOT` или не похожа на SemVer; release job передает
  version из GitHub release tag через `icli.version`.
- `mavenCentralBundle` собирает signed repository bundle с checksums без добавления runtime dependencies.
- release workflow загружает bundle в Central Portal как `USER_MANAGED` deployment; финальная кнопка Publish остается
  ручным шагом в Portal для первого release.

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
- Maven Central publication не требует хранения secrets в repo.
- Публикационный слой остается отдельным от public API и process runtime.

Минусы:

- Первый upload невозможен без verified namespace `io.github.ulviar` в Central Portal.
- Первый Central deployment требует repository secrets `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`, `SIGNING_KEY` и
  `SIGNING_PASSWORD`.
