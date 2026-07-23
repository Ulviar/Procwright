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
- dependency resolution централизован в `settings.gradle.kts`: `FAIL_ON_PROJECT_REPOS`, Maven Central по умолчанию и
  только явно переданный consumer repository с exclusive filter для `io.github.ulviar`;
- JUnit 6 для тестов;
- SnakeYAML Engine только для короткой structured проверки общих GitHub Actions permissions и pinned action refs;
- Spotless + palantir-java-format для форматирования.

## Documentation toolchain

Публичная документация собирается отдельно от runtime artifacts через `./gradlew publicDocsCheck`. Эта задача также
собирает Java Javadocs и подкладывает их в итоговый public site output.

Docs-only top-level dependencies закреплены по версиям в `docs/requirements.txt` и устанавливаются через `uv` в
isolated environment:

- MkDocs;
- Material для MkDocs.

Эти зависимости не попадают в Gradle runtime/test classpath, не являются частью published artifacts и не должны
рассматриваться как process-runtime dependency. Их назначение — strict build публичного сайта из `docs/`.

Transitive Python dependencies закреплены в `docs/requirements.lock` с SHA-256 hashes. `publicDocsCheck` использует
этот lock, а `docs/requirements.txt` остается коротким входным файлом для намеренного обновления top-level docs
tooling. `docsRequirementsLockCheck` проверяет совпадение top-level pins и наличие hashes у всех locked packages.
Workflow устанавливает exact `uv 0.10.12`; обновление lock выполняется намеренно командой из его заголовка той же
версией `uv`.

Public Kotlin KDoc проверяется task `javadocJar` через Dokka 2.2.0 с `reportUndocumented=true` и
`failOnWarning=true`. Dokka является только Gradle
build-time dependency, закреплена dependency-verification SHA-256 metadata, не публикует отдельный сайт и не попадает в
runtime artifacts.

Kotlin ABI проверяется встроенным в Kotlin Gradle Plugin 2.3.21 механизмом ABI validation. Этот gate владеет точным
списком опубликованных Kotlin JVM declarations и не добавляет отдельную dependency.

## Kotlin module

`:procwright-kotlin` optional и не добавляет Kotlin dependency в Java core.

Runtime dependencies модуля:

- Kotlin runtime через Kotlin Gradle plugin 2.3.21;
- `kotlinx-coroutines-core` 1.11.0 для suspending wrappers и Flow adapter.

## Integrations module

`:procwright-integrations` зависит от core module и экспортирует Jackson Databind, потому что adapters принимают и
возвращают `JsonNode`. JSON Lines, delimiter и Content-Length adapters используют существующий core runtime и не
создают второй process engine.

Jackson находится только в optional integrations artifact. `externalLibraryBoundaryCheck` отклоняет Jackson artifacts в
runtime classpath core и Kotlin module; local POM-only consumer проверяет его публикацию из integrations.

Реальный MCP SDK adapter намеренно не входит в текущий module, чтобы не переносить MCP dependency в core или базовый
integration layer.

## Public nullness metadata

`org.jspecify:jspecify:1.0.0` относится к public compile-time metadata только артефактов `procwright` и
`procwright-integrations`. Оба проекта объявляют его как `compileOnlyApi`, а оба JPMS descriptor — как
`requires static transitive org.jspecify`: named consumers видят аннотации при компиляции, но модуль не требуется для
запуска Procwright. В Gradle module metadata dependency присутствует только в `apiElements`, а Maven POM выражает ее
как `compile`, поскольку Maven не имеет эквивалента `compileOnlyApi`.

`procwright-kotlin` не объявляет прямую JSpecify dependency и публикует собственную Kotlin nullability metadata.
JSpecify отсутствует в Gradle `runtimeClasspath` всех трех public projects; эту границу проверяет
`externalLibraryBoundaryCheck`. Сам контракт и его proofs зафиксированы в
[public API baseline](public-api-baseline.md#public-nullness-contract) и
[карте доказательств](../quality/invariant-proof-map.md#api-и-normalization).

## Publication metadata

Три public modules применяют `maven-publish` и содержат sources, Javadoc/KDoc и обязательные POM metadata.
`publishToMavenLocal` разрешен только для Java 17 target. CI проверяет локальные publications внешними consumers как
через Gradle module metadata, так и в принудительном Maven POM-only режиме с `ignoreGradleMetadataRedirection()`.

Конкретный remote registry, signing и credentials до первого release не выбраны и не являются build dependencies.
Они добавляются только вместе с реальным publication path; текущее состояние описано в
[publication-readiness.md](publication-readiness.md).

Gradle dependency verification metadata обновляется при изменении plugin versions или новых build dependencies.

## Process runtime boundary

Public artifacts не зависят от сторонних process runtimes. `externalLibraryBoundaryCheck` проверяет resolved runtime
classpath и прямые dependency declarations core, Kotlin и integrations modules.

## Зависимости CI

GitHub Actions закреплены по commit SHA. CI использует минимальные permissions, проверяет Java 17 artifact на
Linux/macOS/Windows с JDK 17 и на Linux с JDK 21/25; source targets 21/25 отдельно проходят scenario checks на Linux.
Documentation workflow получает `pages: write` и `id-token: write` только в deploy job; build job имеет только
`contents: read`. `WorkflowPolicyTest` запрещает unpinned external actions, `pull_request_target`,
`continue-on-error` и неожиданные write permissions без собственного YAML framework.

## Правило добавления dependency

Новая dependency требует короткого обоснования в ADR или dependency review, если она:

- попадает в runtime classpath публичного артефакта;
- расширяет public API surface;
- приносит platform-specific behavior;
- нужна только для отдельного optional module, но может быть ошибочно воспринята как core dependency.

Если dependency является process-runtime, PTY, expect/prompt automation или другим backend для внешних CLI, нужны ADR,
обновленный dependency review, отдельная module boundary и обновление
`externalLibraryBoundaryCheck` для direct declarations и resolved runtime classpaths публичных модулей.
