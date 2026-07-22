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
- SnakeYAML Engine только для structured проверки security-инвариантов GitHub workflow;
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
runtime classpath core и Kotlin module; exact release POM/module contract отдельно проверяет его публикацию из
integrations.

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

## Publishing dependencies

Publishing/signing setup добавлен для public artifacts:

- root, `:procwright-integrations` и `:procwright-kotlin` применяют `maven-publish`;
- root, `:procwright-integrations` и `:procwright-kotlin` применяют Gradle `signing`;
- POM metadata содержит name, description, project URL, Apache-2.0 license, SCM и developer metadata;
- Maven Central credentials поступают в fixed privileged shell wrapper через `CENTRAL_USERNAME` и
  `CENTRAL_PASSWORD`; wrapper немедленно записывает их в новый owner-only temporary file, удаляет variables из
  environment перед Python/HTTP execution и гарантированно удаляет file при выходе. Credentials не передаются через
  process argv и не выводятся в logs;
- signing material читается только из environment-level secrets `SIGNING_KEY` и `SIGNING_PASSWORD`; импортированный
  primary-key fingerprint должен совпасть с environment-level variable
  `MAVEN_CENTRAL_STAGING_SIGNING_FINGERPRINT`, иначе signing wrapper завершается до
  подписания и не передает полученную из secret key identity как собственный trust root;
- publish tasks fail fast, если `procwright.javaRelease != 17`;
- remote publish tasks fail fast для `*-SNAPSHOT` или non-canonical SemVer version; release workflows и scripts
  используют один strict parser, а manual staging workflow передает выбранную `procwright.version` до создания tag;
- CI smoke проверяет `publishToMavenLocal` на Java 17 и запускает внешние consumer fixtures как через Gradle module
  metadata, так и в принудительном Maven POM-only режиме; POM-only repositories вызывают
  `ignoreGradleMetadataRedirection()`, поэтому marker в опубликованном POM не может незаметно вернуть resolution к
  Gradle module metadata;
- manual `Stage Maven Central` workflow допускает только текущий trusted `main` commit с успешным CI `push` run.
  Непривилегированный job выполняет target build и передает exact immutable handoff; отдельный свежий privileged runner
  проверяет handoff trusted code, подписывает artifacts и атомарно загружает проверенный bundle в Central Portal как
  `USER_MANAGED` deployment. После `VALIDATED` сохраняются bundle, SHA-256, deterministic manifest, provenance и
  нормализованное deployment evidence; target code не выполняется в job с Central/signing secrets;
- Central consumer workflow дожидается `PUBLISHED`, сравнивает со staged bundle все 90 опубликованных base artifacts,
  signatures и checksum sidecars и выполняет
  consumers в обычном и POM-only режимах с dependency verification; два bootstrap candidates создаются в изолированных
  копиях release commit, а deterministic Gradle merge принимает только byte-verified
  `io.github.ulviar:{procwright,procwright-integrations,procwright-kotlin}:<version>` artifacts. External POM checksums
  и любые внешние artifacts из полного consumer graph должны уже находиться в reviewed committed metadata: workflow не
  добавляет их динамически. Финальные normal и POM-only проверки используют отдельные пустые Gradle user homes и явный
  strict dependency verification без metadata write;
- release-triggered docs build требует exact non-draft GitHub Release с API field `immutable == true`, проверяет tag/SHA
  и требует по одному exact producer run/artifact для той же version и commit, связывая trusted workflow ID/revision,
  immutable artifact ID, service digest, raw bytes и canonical staging/consumer content. Manual recovery после
  истечения 90-дневных artifacts требует ту же immutable release identity и заново проверяет на canonical Maven Central
  полный закрытый набор из 90 base/signature/checksum файлов, включая cryptographic verification всех signatures против
  явно approved repository fingerprint/public key, JAR/POM/module semantics и bounded ZIP parsing.
  Co-located Central evidence не является независимой attestation и после expiry не восстанавливает identity исходного
  staged bundle, исторический Portal request/deployment или consumer behavior. После build отдельный deploy job делает
  sparse checkout только trusted verifier scripts и до `deploy-pages` проверяет current-run Pages artifact по upload
  output ID, API digest, raw ZIP/tar и sealed content; target project code в deploy job не выполняется;
- release workflow inputs принимают только lowercase полный 40-символьный commit SHA;
- staging/docs jobs используют Temurin 17.0.19+10, Python 3.13.14, uv 0.10.12 и `ubuntu-24.04`; GitHub Actions
  зафиксированы exact commit SHA, а staging artifact содержит effective build provenance рядом с bundle checksum.

Эти плагины являются build-time tooling и не добавляют runtime dependencies в public artifacts. Gradle dependency
verification metadata должна обновляться при изменении plugin versions или новых build dependencies.

## Process runtime boundary

Public artifacts не зависят от сторонних process runtimes. `externalLibraryBoundaryCheck` проверяет resolved runtime
classpath и прямые dependency declarations core, Kotlin и integrations modules.

## Зависимости CI

GitHub Actions workflow использует:

- `actions/checkout`, pinned to commit SHA;
- `actions/setup-java` с Temurin JDK 17/21/25 matrix, pinned to commit SHA;
- `astral-sh/setup-uv`, pinned to action commit SHA и `uv 0.10.12`;
- `actions/upload-artifact`, pinned to commit SHA для сохранения exact Central bundle и publication proof;
- root workflow permissions пусты; каждый job получает явный минимальный permission map, а `workflowSecurityCheck`
  отклоняет inheritance, omission и escalation;
- docs build job использует только `contents: read`/`actions: read`; deploy job имеет `actions: read`, `contents: read`,
  `pages: write` и `id-token: write`, делает sparse checkout только trusted verifier scripts и не исполняет target code;
- Central Portal credentials и signing material передаются только через environment-level secrets `maven-central`;
  staging и recovery trust roots имеют разные environment-only variable names в `maven-central` и
  `release-recovery`. Оба environments требуют independent reviewer, запрещают self-review и разрешают deployments
  только из `main`; repository-level copies запрещены.

## Правило добавления dependency

Новая dependency требует короткого обоснования в ADR, dependency review или release checklist, если она:

- попадает в runtime classpath публичного артефакта;
- расширяет public API surface;
- приносит platform-specific behavior;
- нужна только для отдельного optional module, но может быть ошибочно воспринята как core dependency.

Если dependency является process-runtime, PTY, expect/prompt automation или другим backend для внешних CLI, одного
release checklist entry недостаточно. Нужны ADR, обновленный dependency review, отдельная module boundary и обновление
`externalLibraryBoundaryCheck` для direct declarations и resolved runtime classpaths публичных модулей.
