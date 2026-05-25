# ADR-0013: Публичная документация и documentation toolchain

## Статус

Accepted.

## Контекст

Независимое scenario testing показало, что iCLI выигрывает за счет scenario-first API, но documentation discoverability
остается главным риском. Ветка уже содержит русскоязычный `context/` как внутреннюю память проекта, Javadoc artifacts
для Java modules, KDoc coverage check для Kotlin API и compile-tested examples. Этого недостаточно для пользователя,
который впервые открывает библиотеку и должен быстро выбрать правильный сценарий.

Нужно добавить публичный слой документации, не смешивая его с внутренним context:

- `context/` остается русскоязычной проектной памятью, ADR и audit trail;
- публичные docs должны быть ориентированы на пользователя и написаны на английском вместе с API;
- документация не должна обещать поведение раньше tests/examples;
- framework должен усиливать навигацию и проверяемость, а не становиться отдельным product site.

## Решение

Использовать:

- Diátaxis как информационную архитектуру: tutorials, how-to guides, reference, explanation;
- MkDocs как Markdown-based static documentation builder;
- Material for MkDocs как публичную тему с поиском и навигацией;
- Javadoc как generated Java API reference;
- Dokka как будущий generated Kotlin API reference, когда Kotlin public API стабилизируется для публикации.

Публичный docs source живет в `docs/`, конфигурация — в `mkdocs.yml`, Python docs dependencies — в
`docs/requirements.txt`.

Gradle получает отдельный gate `publicDocsCheck`, который запускает MkDocs в strict mode через docs requirements с
закрепленными top-level versions. Release candidate gate включает этот шаг, но обычный `check` остается
runtime-oriented и не превращается в Python/docs pipeline.

## Инварианты

- `docs/` описывает только реализованное и проверенное поведение.
- Public docs не являются источником истины для runtime guarantees; они ссылаются на tested behavior, examples и API
  docs.
- Public scenario docs должны оставаться синхронизированы с compile-tested examples.
- `context/` не публикуется как пользовательская документация без редакторской переработки.
- Javadoc/KDoc остаются частью public API discipline, а не заменой scenario docs.
- Документационный framework не должен требовать React/MDX или frontend runtime, пока нет доказанной потребности.
- Transitive Python docs dependencies пока не зафиксированы lockfile или hash-pinned constraints; это остаточный
  supply-chain риск documentation toolchain, не runtime artifact. Перед публичным release следует либо добавить lock
  workflow, либо явно принять этот риск в release checklist.

## Отклоненные варианты

### Только README и Javadocs

Отклонено, потому что iCLI продает не набор классов, а сценарную модель. README слишком мал для scenario cookbook, а
Javadocs плохо объясняют выбор workflow.

### Docusaurus

Отклонено для текущего этапа как лишний frontend/MDX слой. Он уместен, если позже понадобятся интерактивные React
components, полноценная multi-version/i18n product site модель или docs portal вокруг нескольких продуктов.

### VitePress

Отклонено как хороший, но не более подходящий вариант для текущей JVM library docs. Vue/Vite stack не дает iCLI
очевидного выигрыша над MkDocs Material.

### Antora + AsciiDoc

Отклонено до появления настоящей multi-repo или large-scale multi-version документации. Сейчас это добавило бы больше
процесса, чем пользы.

### Sphinx

Отклонено как менее естественный выбор для JVM-библиотеки. Он силен для Python/docstring-oriented проектов, но iCLI
уже имеет Java/Kotlin-native API docs path.

## Последствия

Плюсы:

- появляется публичная входная точка для пользователя;
- scenario-first философия отражается в структуре docs;
- docs build можно проверять отдельно и в release gate;
- внутренний русский context не смешивается с пользовательской документацией;
- Javadoc/Dokka остаются специализированными API reference слоями.

Минусы:

- появляется Python docs toolchain;
- release gate требует доступного `uv` и возможности установить docs dependencies из hash-pinned lock;
- docs toolchain lock нужно обновлять осознанно при изменении top-level requirements;
- нужно поддерживать синхронизацию публичных scenario pages и compile-tested examples.

## Проверка

- `./gradlew publicDocsCheck` собирает публичный MkDocs site в strict mode.
- `./gradlew releaseCandidateCheck` включает `publicDocsCheck`.
- Public scenario docs coverage test проверяет, что страница сценариев упоминает все core compile-tested examples.
- Javadocs для Java modules и KDoc checks для Kotlin module остаются отдельными gates.
