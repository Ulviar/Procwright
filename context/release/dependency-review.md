# Обзор зависимостей

## Core module

Runtime dependencies отсутствуют. Core module должен оставаться легкой Java-библиотекой вокруг JDK process APIs,
validation objects, runtime policies и diagnostics.

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

## Comparison module

`:icli-comparison` — исследовательский модуль, не runtime dependency core и не часть пользовательского API. Он
подключает внешние библиотеки для сравнения сценариев:

- Apache Commons Exec;
- ZeroTurnaround zt-exec;
- NuProcess;
- Pty4J;
- ExpectIt.

Эти зависимости допустимы только внутри comparison module. Они не должны протекать в `:`, `:icli-kotlin` или
`:icli-integrations`.

## CI dependencies

GitHub Actions workflow использует:

- `actions/checkout`;
- `actions/setup-java` с Temurin JDK 25.

## Правило добавления dependency

Новая dependency требует короткого обоснования в ADR или release note, если она:

- попадает в runtime classpath публичного артефакта;
- расширяет public API surface;
- приносит platform-specific behavior;
- нужна только для отдельного optional module, но может быть ошибочно воспринята как core dependency.
