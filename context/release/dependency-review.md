# Обзор зависимостей

## Core module

Runtime dependencies отсутствуют. Core module должен оставаться легкой Java-библиотекой вокруг JDK process APIs,
validation objects, runtime policies и diagnostics.

PTY support в core находится за `PtyProvider` SPI и не добавляет runtime dependency. Текущий system provider использует
доступный в ОС `script(1)` command как platform capability; Pty4J, ConPTY wrappers и другие native bindings не входят в
core classpath.

Build/test dependencies:

- Gradle wrapper;
- Gradle wrapper distribution закреплен `distributionSha256Sum`; wrapper files обновляются только через Gradle wrapper
  task и проходят review как build tooling change;
- Gradle dependency verification metadata (`gradle/verification-metadata.xml`) фиксирует SHA-256 для build/test
  dependencies;
- dependency resolution централизован в `settings.gradle.kts`: `FAIL_ON_PROJECT_REPOS` и Maven Central как единственный
  repository;
- JUnit 6 для тестов;
- Spotless + palantir-java-format для форматирования.

## Documentation toolchain

Публичная документация собирается отдельно от runtime artifacts через `./gradlew publicDocsCheck`. Эта задача также
собирает Java Javadocs и подкладывает их в итоговый public site output.

Docs-only top-level dependencies закреплены по версиям в `docs/requirements.txt` и устанавливаются через `uv` в
isolated environment:

- MkDocs;
- Material for MkDocs.

Эти зависимости не попадают в Gradle runtime/test classpath, не являются частью published artifacts и не должны
рассматриваться как process-runtime dependency. Их назначение — strict build публичного сайта из `docs/`.

Transitive Python dependencies закреплены в `docs/requirements.lock` с SHA-256 hashes. `publicDocsCheck` использует
этот lock, а `docs/requirements.txt` остается коротким входным файлом для намеренного обновления top-level docs
tooling.

## Kotlin module

`:procwright-kotlin` optional и не добавляет Kotlin dependency в Java core.

Runtime dependencies модуля:

- Kotlin runtime через Kotlin Gradle plugin;
- `kotlinx-coroutines-core` для suspending wrappers и Flow adapter.

## Integrations module

`:procwright-integrations` зависит от core module и не добавляет внешних runtime dependencies. JSON/JSONL, Content-Length
framing, cancellable calls и command-backed tool wrappers реализованы внутри модуля.

Реальный MCP SDK adapter намеренно не входит в текущий module, чтобы не переносить MCP dependency в core или базовый
integration layer.

## Publishing dependencies

Publishing/signing setup добавлен для public artifacts:

- root, `:procwright-integrations` и `:procwright-kotlin` применяют `maven-publish`;
- root, `:procwright-integrations` и `:procwright-kotlin` применяют Gradle `signing`;
- POM metadata содержит name, description, project URL, Apache-2.0 license, SCM и developer metadata;
- Maven Central credentials читаются только из `CENTRAL_USERNAME` и `CENTRAL_PASSWORD`;
- signing material читается только из `SIGNING_KEY` и `SIGNING_PASSWORD`;
- publish tasks fail fast, если `procwright.javaRelease != 17`;
- remote publish tasks fail fast для `*-SNAPSHOT` или non-SemVer version; release job передает `procwright.version` из GitHub
  release tag;
- CI smoke проверяет `publishToMavenLocal` на Java 17;
- release event собирает signed Maven Central bundle и загружает его в Central Portal как `USER_MANAGED` deployment.

Эти плагины являются build-time tooling и не добавляют runtime dependencies в public artifacts. Gradle dependency
verification metadata должна обновляться при изменении plugin versions или новых build dependencies.

## Модуль сравнения

`:procwright-comparison` — исследовательский модуль, не runtime dependency core и не часть пользовательского API. Он
подключает внешние библиотеки для сравнения сценариев:

- Apache Commons Exec;
- ZeroTurnaround zt-exec;
- NuProcess;
- Pty4J;
- ExpectIt.

JMH dependencies (`org.openjdk.jmh:jmh-core` и annotation processor) допустимы только внутри comparison module и только
для benchmark source set.
`org.slf4j:slf4j-api` также допустим только внутри comparison module: он нужен локальному no-op provider, который
подавляет logging noise внешних comparison-библиотек без добавления полноценного logging backend.

Comparison `JavaExec`/JMH tasks явно задают JVM flags для native/Unsafe access, чтобы предупреждения JNA/JMH не
засоряли regression output. Эти flags не применяются к core runtime tasks.

Эти зависимости допустимы только внутри comparison module. Они не должны протекать в `:`, `:procwright-kotlin` или
`:procwright-integrations`.

Регрессионный gate: `ExternalLibraryBoundaryTest` проверяет, что process-library и JMH dependencies объявлены только в
`procwright-comparison/build.gradle.kts`, source files публичных артефактов не импортируют и не используют packages comparison
libraries, а публичные build files не зависят от `:procwright-comparison` и не объявляют comparison/JMH dependencies.
`externalLibraryBoundaryCheck` дополнительно проверяет resolved runtime classpath публичных модулей и прямые dependency
declarations публичных modules.

## Зависимости CI

GitHub Actions workflow использует:

- `actions/checkout`, pinned to commit SHA;
- `actions/setup-java` с Temurin JDK 17/21/25 matrix, pinned to commit SHA;
- минимальные default workflow permissions: `contents: read`;
- release-only publish job использует только `contents: read`; Central Portal credentials и signing material передаются
  через repository secrets.

## Правило добавления dependency

Новая dependency требует короткого обоснования в ADR, dependency review или release checklist, если она:

- попадает в runtime classpath публичного артефакта;
- расширяет public API surface;
- приносит platform-specific behavior;
- нужна только для отдельного optional module, но может быть ошибочно воспринята как core dependency.

Если dependency является process-runtime, PTY, expect/prompt automation или другим backend для внешних CLI, одного
release checklist entry недостаточно. Нужны ADR, обновленный dependency review, отдельная module boundary и обновление
`ExternalLibraryBoundaryTest`/`externalLibraryBoundaryCheck`.
