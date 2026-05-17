# Обзор зависимостей

## Core module

Runtime dependencies отсутствуют. Core module должен оставаться легкой Java-библиотекой вокруг JDK process APIs,
validation objects, runtime policies и diagnostics.

PTY support в core находится за `PtyProvider` SPI и не добавляет runtime dependency. Текущий system provider использует
доступный в ОС `script(1)` command как platform capability; Pty4J, ConPTY wrappers и другие native bindings не входят в
core classpath.

Build/test dependencies:

- Gradle wrapper;
- JUnit 6 для тестов;
- Spotless + palantir-java-format для форматирования.

## Kotlin module

`:icli-kotlin` optional и не добавляет Kotlin dependency в Java core.

Runtime dependencies модуля:

- Kotlin runtime через Kotlin Gradle plugin;
- `kotlinx-coroutines-core` для suspending wrappers и Flow adapter.

## Integrations module

`:icli-integrations` зависит от core module и не добавляет внешних runtime dependencies. JSON/JSONL, Content-Length
framing, cancellable calls и command-backed tool wrappers реализованы внутри модуля.

Реальный MCP SDK adapter намеренно не входит в текущий module, чтобы не переносить MCP dependency в core или базовый
integration layer.

## Модуль сравнения

`:icli-comparison` — исследовательский модуль, не runtime dependency core и не часть пользовательского API. Он
подключает внешние библиотеки для сравнения сценариев:

- Apache Commons Exec;
- ZeroTurnaround zt-exec;
- NuProcess;
- Pty4J;
- ExpectIt.

JMH dependencies (`org.openjdk.jmh:jmh-core` и annotation processor) допустимы только внутри comparison module и только
для benchmark source set.

Эти зависимости допустимы только внутри comparison module. Они не должны протекать в `:`, `:icli-kotlin` или
`:icli-integrations`.

Регрессионный gate: `ExternalLibraryBoundaryTest` проверяет, что process-library и JMH dependencies объявлены только в
`icli-comparison/build.gradle.kts`, source files публичных артефактов не импортируют и не используют packages comparison
libraries, а публичные build files не зависят от `:icli-comparison` и не объявляют comparison/JMH dependencies.
`externalLibraryBoundaryCheck` дополнительно проверяет resolved runtime classpath публичных модулей и прямые dependency
declarations публичных modules.

## Зависимости CI

GitHub Actions workflow использует:

- `actions/checkout`;
- `actions/setup-java` с Temurin JDK 25.

## Правило добавления dependency

Новая dependency требует короткого обоснования в ADR или release note, если она:

- попадает в runtime classpath публичного артефакта;
- расширяет public API surface;
- приносит platform-specific behavior;
- нужна только для отдельного optional module, но может быть ошибочно воспринята как core dependency.

Если dependency является process-runtime, PTY, expect/prompt automation или другим backend для внешних CLI, release note
недостаточно. Нужны ADR, обновленный dependency review, отдельная module boundary и обновление
`ExternalLibraryBoundaryTest`/`externalLibraryBoundaryCheck`.
