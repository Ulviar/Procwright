# ADR-0017: Стратегия публикации release artifacts

## Статус

Accepted as a pre-publishing decision.

## Контекст

Проект подготовил release hardening baseline: group/version, source/Javadoc artifacts для Java modules, public docs,
license, dependency review и release checklist. Но реальная публикация в Maven Central требует signing, metadata,
credential handling и проверки supply-chain границ. Эти изменения рискованнее обычной документации и не должны
появляться как побочный эффект стабилизации API.

## Решение

Первый release-candidate stabilization pass не добавляет Maven Central publishing plugin, signing setup или credential
handling.

Планируемые coordinates остаются:

- `com.github.ulviar:icli`;
- `com.github.ulviar:icli-kotlin`;
- `com.github.ulviar:icli-integrations`.

Перед публичной публикацией нужен отдельный implementation step:

- выбрать publishing plugin и signing flow;
- добавить POM metadata: name, description, URL, license, SCM, developers;
- настроить reproducible local publication check;
- обновить dependency verification metadata после build tooling changes;
- обновить release checklist и public installation docs с фактическими coordinates;
- прогнать полный release gate.

## Инварианты

- Publishing setup не должен добавлять runtime dependency в public artifacts.
- Credentials не хранятся в репозитории.
- Published artifacts должны соответствовать JPMS/package boundary tests.
- Source и Javadoc artifacts обязательны для Java modules.
- Kotlin artifact остается optional и не становится dependency core module.

## Последствия

Плюсы:

- Release stabilization не смешивается с credential-sensitive инфраструктурой.
- Public docs честно говорят, что stable artifact еще не опубликован.
- Maven Central задача остается отдельным reviewable change.

Минусы:

- Первый RC пока нельзя установить из Maven Central.
- Перед публикацией потребуется еще один release-focused PR/commit.
