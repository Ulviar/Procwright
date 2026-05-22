# Public API baseline

## Назначение

Этот документ фиксирует intended public surface перед первым release candidate. Источник правды для точного списка
public types находится в тестах, чтобы любое случайное расширение API ломало обычный verification gate.

## Core module

Модуль `com.github.ulviar.icli` экспортирует только пакеты:

- `com.github.ulviar.icli`;
- `com.github.ulviar.icli.command`;
- `com.github.ulviar.icli.diagnostics`;
- `com.github.ulviar.icli.preset`;
- `com.github.ulviar.icli.session`;
- `com.github.ulviar.icli.terminal`.

Публичная поверхность core проверяется тестом
`src/test/java/com/github/ulviar/icli/PublicApiSurfaceTest.java`.

Этот тест является release-relevant guard:

- точный набор public API types должен совпадать с approved baseline;
- JPMS descriptor должен экспортировать только approved public packages;
- публичные signatures могут ссылаться только на JDK types или approved iCLI public API types;
- non-exported `internal` packages не считаются пользовательским API даже если отдельные implementation classes имеют
  `public` modifier из-за межпакетных границ внутри модуля.

Scenario API baseline первого RC включает `run`, `interactive`, `expect`, `lineSession`, `protocolSession`, `listen`,
`lineSession().pooled()` и `protocolSession(factory).pooled()`. Generic/core async request API и raw session pooling не
входят в текущую baseline; узкий cancellable JSON Lines helper остается частью optional integrations module.

## Optional integrations module

Модуль `com.github.ulviar.icli.integrations` экспортирует только пакет
`com.github.ulviar.icli.integration` и requires core module transitively, потому что публичные helpers возвращают и
принимают core protocol/session types.

Публичная поверхность optional integration layer проверяется тестом
`icli-integrations/src/test/java/com/github/ulviar/icli/integration/PublicIntegrationApiSurfaceTest.java`.

Новые public types в этом модуле допустимы только если они остаются тонким layer над scenario-first core и не добавляют
process-runtime dependency в public artifacts.

## Optional Kotlin module

Модуль `:icli-kotlin` публикует только package `com.github.ulviar.icli.kotlin`.

Публичная поверхность Kotlin ergonomics layer проверяется тестом
`icli-kotlin/src/test/kotlin/com/github/ulviar/icli/kotlin/PublicKotlinApiSurfaceTest.kt`.

Новые Kotlin extensions допустимы только если они не создают второй dialect поверх Java core и сохраняют явный выбор
сценария.

## Правило изменения baseline

Любое изменение approved public API types требует:

- изменения соответствующего public API surface test;
- обновления compile-tested examples, если меняется пользовательская форма вызова;
- обновления публичной документации в `docs/`;
- обновления release notes или ADR, если изменение является breaking или расширяет сценарную модель.
