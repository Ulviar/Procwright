# Public API baseline

## Назначение

Этот документ фиксирует intended public surface для baseline `0.1.0`. Защита состоит из двух слоев:

- public API surface tests фиксируют approved public packages и public types;
- `apiCompatibilityCheck` сравнивает текущие JVM signatures core, integrations и Kotlin artifacts с машинным baseline
  в `config/api-compatibility/0.1.0/`.

Такой guard ловит не только случайное удаление типа, но и изменение/добавление публичной JVM-сигнатуры без явного
решения.

## Core module

Модуль `io.github.ulviar.icli` экспортирует только пакеты:

- `io.github.ulviar.icli`;
- `io.github.ulviar.icli.command`;
- `io.github.ulviar.icli.diagnostics`;
- `io.github.ulviar.icli.preset`;
- `io.github.ulviar.icli.session`;
- `io.github.ulviar.icli.terminal`.

Публичная поверхность core проверяется тестом
`src/test/java/io/github/ulviar/icli/PublicApiSurfaceTest.java`.

Эти проверки являются release-relevant guard:

- точный набор public API types должен совпадать с approved baseline;
- JPMS descriptor должен экспортировать только approved public packages;
- публичные signatures могут ссылаться только на JDK types или approved iCLI public API types;
- public JVM signatures должны совпадать с `config/api-compatibility/0.1.0/icli.txt`;
- non-exported `internal` packages не считаются пользовательским API даже если отдельные implementation classes имеют
  `public` modifier из-за межпакетных границ внутри модуля.

Scenario API baseline `0.1.0` включает `run`, `interactive`, `expect`, `lineSession`, `protocolSession`, `listen`,
`lineSession().pooled()` и `protocolSession(factory).pooled()`. Generic/core async request API и raw session pooling не
входят в текущую baseline; узкий cancellable JSON Lines helper остается частью optional integrations module.

## Optional integrations module

Модуль `io.github.ulviar.icli.integrations` экспортирует только пакет
`io.github.ulviar.icli.integration` и requires core module transitively, потому что публичные helpers возвращают и
принимают core protocol/session types.

Публичная поверхность optional integration layer проверяется тестом
`icli-integrations/src/test/java/io/github/ulviar/icli/integration/PublicIntegrationApiSurfaceTest.java`.
JVM signatures проверяются against `config/api-compatibility/0.1.0/icli-integrations.txt`.

Новые public types в этом модуле допустимы только если они остаются тонким layer над scenario-first core и не добавляют
process-runtime dependency в public artifacts.

## Optional Kotlin module

Модуль `:icli-kotlin` публикует только package `io.github.ulviar.icli.kotlin`.

Публичная поверхность Kotlin ergonomics layer проверяется тестом
`icli-kotlin/src/test/kotlin/io/github/ulviar/icli/kotlin/PublicKotlinApiSurfaceTest.kt`.
JVM signatures проверяются against `config/api-compatibility/0.1.0/icli-kotlin.txt`.

Новые Kotlin extensions допустимы только если они не создают второй dialect поверх Java core и сохраняют явный выбор
сценария.

## Правило изменения baseline

Любое изменение approved public API types требует:

- изменения соответствующего public API surface test;
- обновления machine-readable baseline через `./gradlew writeApiCompatibilityBaseline --project-prop=icli.javaRelease=17`;
- обновления compile-tested examples, если меняется пользовательская форма вызова;
- обновления публичной документации в `docs/`;
- обновления ADR, если изменение является breaking или расширяет сценарную модель.
