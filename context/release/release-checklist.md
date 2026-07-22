# Релизный checklist

Этот список применяется к публичным релизам Procwright.

## Перед freeze

- README описывает только реализованное и протестированное поведение.
- `context/quality/scorecard.md` не содержит устаревшего статуса по уже завершенным фазам.
- Все ADR для публичных архитектурных решений добавлены в `context/decisions/`.
- Release docs не создают upgrade-разделы для несуществующих прошлых пользователей.
- Versioning и compatibility policies актуальны для текущего Java/Kotlin baseline.
- Приняты стабилизационные решения по public API, PTY/platform strategy, publishing strategy и Kotlin generated docs.
- Approved public API surface зафиксирован в [public-api-baseline.md](public-api-baseline.md) и проверяется exact baseline
  tests для core и integrations; Kotlin module дополнительно проверяется встроенной Kotlin Gradle Plugin ABI validation.
- Dependency review не содержит неизвестных runtime dependencies.
- Gradle wrapper distribution checksum и dependency verification metadata актуальны после каждого изменения
  build/test dependencies.
- `docs/requirements.lock` актуален после изменения docs toolchain, содержит SHA-256 для каждого package и проходит
  `docsRequirementsLockCheck`; CI использует закрепленный `uv 0.10.12`.
- Publishing/signing setup реализован по ADR-0017; remote publish запрещает `*-SNAPSHOT` и non-SemVer version, а
  публичный artifact считается готовым к публикации только после Java 17-targeted local publication check и CI job с
  Central Portal/signing secrets.
- `releaseWorkflowStaticCheck` parsing-ом YAML 1.2 проверяет exact triggers, inputs, action SHA, root/job permissions,
  critical `run` scalars и порядок release steps; тот же gate выполняет hostile fixtures и `bash -n` fixed release
  scripts. `releaseEvidenceScriptSelfTest` проверяет canonical version/commit contract, immutable release response и
  bounded Central evidence verifiers. В non-SNAPSHOT режиме `realReleaseArtifactSemanticTest` публикует три
  фактических модуля в изолированный repository и выполняет полный unsigned handoff -> real GPG signing -> 90-file
  staged verifier roundtrip, затем проверяет тем же production verifier точное сочетание signed bundle и 15
  сгенерированных Gradle Maven metadata files.

## Локальные проверки

Обязательный набор:

```bash
./gradlew spotlessCheck --project-prop=procwright.javaRelease=17
./gradlew quickCheck --project-prop=procwright.javaRelease=17
./gradlew scenarioCheck --project-prop=procwright.javaRelease=17
./gradlew regressionCheck --project-prop=procwright.javaRelease=17
./gradlew check --rerun-tasks --project-prop=procwright.javaRelease=17
./gradlew publicJavaJavadocCheck --rerun-tasks --project-prop=procwright.javaRelease=17
./gradlew :procwright-kotlin:checkKotlinAbi --rerun-tasks --project-prop=procwright.javaRelease=17
./gradlew publicDocsCheck --project-prop=procwright.javaRelease=17
./gradlew releaseCandidateCheck --project-prop=procwright.javaRelease=17
git diff --check
git diff --exit-code
```

`quickCheck`, `scenarioCheck` и `regressionCheck` образуют явную цепочку named tiers.
`releaseCandidateCheck` имеет два режима под одним task name. С default SNAPSHOT version он агрегирует readiness checks,
public Javadocs/docs, optional module checks, consumer fixtures, formatting и release script/contract self-tests, но не
выбирает `releaseDocsContentCheck` и `realReleaseArtifactSemanticTest`. С explicit non-SNAPSHOT `procwright.version` он
дополнительно выбирает обе release-only проверки; publication/signing guards остаются обязательными. Обычный
`./gradlew check` остается отдельной lifecycle-проверкой и не определяет состав named tiers. Comparison/JMH tasks не
являются release pass/fail gate.

Назначение уровней описано в [../evals/test-tiers.md](../evals/test-tiers.md). `releaseCandidateCheck` является
локальным составным gate и требует clean worktree, включая untracked files. `cleanWorkingTreeCheck` выполняется после
всех проверок, выбранных текущим режимом.

Если `releaseCandidateCheck` недоступен в окружении, clean worktree проверяется эквивалентом
`git status --porcelain=v1 --untracked-files=all`: вывод должен быть пустым.

`spotlessApply` допустим как repair-команда до release gate, но не как сама проверка.

Сценарный release gate:

- новое API расширяет один из канонических сценариев (`run`, `lineSession`, `protocolSession`, `interactive`, `expect`,
  `listen`, `lineSession().pooled()`, `protocolSession(factory).pooled()`) или optional integration layer;
- новое/измененное API обновляет exact public API baseline соответствующего модуля; generic checked `throws`
  методов/конструкторов остаются частью сравниваемой signature;
- новые/измененные public entry points, examples и tests сверены с
  [../scenario-contracts.md](../scenario-contracts.md);
- новые/измененные cookbook recipes сверены с [../scenario-cookbook.md](../scenario-cookbook.md), а canonical examples
  компилируются и выполняются внешними consumer fixtures;
- публичные docs в `docs/` собираются через `publicDocsCheck` и не обещают behavior без tests/examples;
- dependency-specific types не протекают в core public API;
- external process-library dependencies из comparison не протекают в публичные artifacts;
- terminal/PTY возможности остаются capability/transport boundary;
- terminal methods остаются только в session-family API, а `run`/`listen` не получают PTY knobs без нового ADR;
- diagnostics event schema и redaction contract остаются согласованными с [../diagnostics.md](../diagnostics.md);
- security-sensitive invariants имеют regression tests: process-tree shutdown, clean environment, bounded line length,
  expect transcript redaction, JSON depth limit.

## CI

Каждая ячейка GitHub Actions matrix должна независимо компилировать Java 17-targeted build и запускать
`./gradlew check` и `./gradlew publicJavaJavadocCheck` на Temurin JDK 17, 21 или 25 для соответствующей платформы:

- Linux;
- macOS;
- Windows.

Отдельные Linux jobs должны собирать source targets 21 и 25 на соответствующих JDK. Они дополняют, а не заменяют
независимые Java 17-targeted matrix builds.

POSIX-only и PTY-only tests должны skip-аться через assumptions, если соответствующая capability не гарантирована
runner-ом. Linux/JDK 17 matrix job является намеренным исключением: workflow сначала проверяет `script(1)`, задает
`procwright.requireSystemPty=true`, а integration test требует доступный system provider. Это не дает всей PTY-группе
незаметно пройти через skips.

Root workflow permissions должны оставаться пустыми, каждый job обязан объявлять exact минимальный permission map, а
external actions должны быть pinned to commit SHA.

## Перед публикацией

- Версия больше не `0.0.0-SNAPSHOT`.
- Public release docs перечисляют shipped behavior, known limitations и только реальные breaking changes для
  опубликованных API, если такие появятся.
- Source и Javadoc artifacts собираются для Java modules.
- Public MkDocs site собирается в strict mode и включает generated Java API docs.
- Kotlin public API задокументирован через KDoc в sources artifact; `:procwright-kotlin:kotlinApiDocsCheck` запускает
  Dokka parser-backed проверку с `reportUndocumented=true` и `failOnWarning=true`.
- Kotlin binary API проверяется `:procwright-kotlin:checkKotlinAbi` относительно committed baseline.
- Отдельный generated Kotlin API site не публикуется.
- License file присутствует в корне репозитория.
- POM metadata соответствует Apache-2.0 license, SCM и planned coordinates.
- Manual staging workflow передает выбранную non-SNAPSHOT version через `procwright.version` до создания release tag.
- Central Portal namespace `io.github.ulviar` verified.
- В environment `maven-central` доступны только environment-level secrets `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`,
  `SIGNING_KEY`, `SIGNING_PASSWORD` и variable `MAVEN_CENTRAL_STAGING_SIGNING_FINGERPRINT`. Variable содержит exact
  uppercase fingerprint ожидаемого secret key; trusted shell step и signing wrapper проверяют его до подписания.
- В environment `release-recovery` доступны только environment-level variables
  `MAVEN_CENTRAL_RECOVERY_SIGNING_FINGERPRINT` и `MAVEN_CENTRAL_RECOVERY_SIGNING_PUBLIC_KEY`.
- Для `maven-central` и `release-recovery` настроены минимум один независимый required reviewer, `Prevent self-review`
  и deployment branch policy `Selected branches and tags` только для `main`. Repository-level secrets/variables с
  этими или прежними общими names отсутствуют; иначе GitHub scope fallback может обойти удаление environment value.
- До создания первого публичного release включен repository setting **Settings -> Releases -> Enable release
  immutability**. Проверка должна вернуть HTTP `200` и JSON `{"enabled":true,...}`; HTTP `404` означает, что setting не
  включен:

```bash
gh api \
  -H 'Accept: application/vnd.github+json' \
  -H 'X-GitHub-Api-Version: 2026-03-10' \
  repos/Ulviar/Procwright/immutable-releases
```

Включение является отдельным административным действием и не выполняется release workflow:

```bash
gh api --method PUT \
  -H 'Accept: application/vnd.github+json' \
  -H 'X-GitHub-Api-Version: 2026-03-10' \
  repos/Ulviar/Procwright/immutable-releases
```
- Local publication check проходит:

```bash
repository="$(mktemp -d)/procwright-m2"
./gradlew publishToMavenLocal \
  -Dmaven.repo.local="$repository" \
  --project-prop=procwright.javaRelease=17 \
  --project-prop=procwright.version=0.1.0
./gradlew \
  :procwright-consumer-examples:test \
  :procwright-integrations-consumer-example:check \
  :procwright-kotlin-consumer-example:check \
  --project-prop=procwright.javaRelease=17 \
  --project-prop=procwright.consumerVersion=0.1.0 \
  --project-prop=procwright.consumerRepository="$repository" \
  --dependency-verification=off \
  --refresh-dependencies \
  --rerun-tasks
./gradlew \
  :procwright-consumer-examples:test \
  :procwright-integrations-consumer-example:check \
  :procwright-kotlin-consumer-example:check \
  --project-prop=procwright.javaRelease=17 \
  --project-prop=procwright.consumerVersion=0.1.0 \
  --project-prop=procwright.consumerRepository="$repository" \
  --project-prop=procwright.consumerPomOnly=true \
  --dependency-verification=off \
  --refresh-dependencies \
  --rerun-tasks
```

Эти fixtures объявляют по одной внешней Procwright dependency: core, integrations или Kotlin artifact. Группа
`io.github.ulviar` разрешается исключительно из указанного isolated repository, поэтому отсутствующий локальный artifact
не может незаметно прийти из Central. Первый запуск проверяет обычный Gradle consumer path с module metadata, второй
разрешает metadata только через Maven POM без artifact-only fallback. Вместе они доказывают transitive metadata optional
modules и компиляцию примеров. Verification
отключается только для consumer build против созданного тем же шагом isolated repository: checksum собственного нового
artifact заранее отсутствует в committed metadata. Обычные CI builds продолжают проверять внешние dependencies.

- Signed Central bundle собирается локально или в CI:

```bash
./gradlew mavenCentralBundle --project-prop=procwright.javaRelease=17 --project-prop=procwright.version=0.1.0
```

Локальная задача проверяет exact 90-file contract, отсутствие лишних classifiers/sidecars, byte equality repository и
ZIP, semantic identity JAR/sources/Javadoc/POM/module metadata, наличие detached signature для каждого base artifact и
создает checksums. В основном workflow привилегированный handoff дополнительно криптографически проверяет каждую
созданную подпись против того же приватного snapshot до сборки ZIP; Central Portal повторно проверяет bundle при
validation deployment.

## Публикация первой версии

1. Выбрать SemVer version, например `0.1.0`, но пока не создавать tag или публичный GitHub Release.
2. Подготовить один release commit: заменить pre-release installation path на release coordinates и направить API links
   на адреса, которые опубликует documentation workflow. При non-SNAPSHOT version задача
   `releaseDocsContentCheck` блокирует pre-release формулировки, moving source links и coordinates другой версии.
   Отдельные release notes для первого выпуска не требуются: пользовательские сведения находятся в README и public docs.
3. На этом clean commit прогнать `releaseCandidateCheck --project-prop=procwright.javaRelease=17 --project-prop=procwright.version=<version>`
   и дождаться зеленого push-run полного CI для Java 17/21/25 на Linux, macOS и Windows. Зафиксировать полный commit
   SHA; после staging его содержимое больше не меняется.
4. В environment `maven-central` задать `MAVEN_CENTRAL_STAGING_SIGNING_FINGERPRINT`; в environment `release-recovery`
   задать `MAVEN_CENTRAL_RECOVERY_SIGNING_FINGERPRINT` и `MAVEN_CENTRAL_RECOVERY_SIGNING_PUBLIC_KEY`. Fingerprints
   содержат один и тот же 40-символьный uppercase primary-key fingerprint; public key является bounded ASCII-armored
   key той же identity без private material. Не создавать repository-level копии. Проверить required reviewers,
   `Prevent self-review` и restriction только на `main` для обоих environments.
5. Запустить manual workflow `Stage Maven Central`, передав выбранную version и lowercase полный commit SHA. Workflow
   допускает только текущий `main` commit, связанный с успешным CI `push` run, и использует scripts из того же trusted
   workflow revision. Непривилегированный job повторно выполняет release gate и создает unsigned handoff. Новый runner с
   environment `maven-central` проверяет raw ZIP и semantic identity exact handoff, связывает manifest с digest/size/role всех
   15 base artifacts, создает owner-only snapshots, подписывает и криптографически проверяет каждый snapshot, добавляет
   четыре checksum sidecars к каждому и загружает закрытый 90-файловый bundle как `USER_MANAGED` deployment. Target code на этом runner не
   выполняется. Staging считается успешным только после состояния Central `VALIDATED`; рядом с bundle сохраняются его
   SHA-256, deterministic manifest, provenance и нормализованное deployment evidence. Более новые commits в `main` не
   меняют уже проверенный release commit и не отменяют staging.
6. В Central Portal проверить validation results и вручную нажать Publish.
7. Запустить workflow `Consumer Smoke Maven Central` для той же version и lowercase полного commit SHA. Привилегированный
   job на свежем runner проверяет exact staging evidence, под одним monotonic deadline ждет Central state `PUBLISHED`
   и byte-for-byte сравнивает с bundle все 90 опубликованных файлов: primary, sources и Javadoc JAR, POM, Gradle module metadata, подписи и checksum
   sidecars. Следующий непривилегированный job в изолированных копиях строит verification metadata candidates для
   обычного и POM-only resolution и fail-closed объединяет только checksums трех byte-verified release modules. Любое
   изменение baseline, удаление, внешний dynamic checksum или unrelated addition отклоняется. После merge оба consumer
   режима выполняются в отдельных пустых Gradle user homes с явным strict dependency verification без
   `--write-verification-metadata`.
8. До создания release включить repository setting `Settings` -> `General` -> `Releases` ->
   `Enable release immutability`. Эквивалентная admin API-команда:
   `gh api --method PUT -H 'X-GitHub-Api-Version: 2026-03-10' repos/Ulviar/Procwright/immutable-releases`.
   Проверка `gh api -H 'X-GitHub-Api-Version: 2026-03-10' repos/Ulviar/Procwright/immutable-releases` должна вернуть
   HTTP 200 и JSON с `enabled: true`. Затем создать tag `v<version>` строго на зафиксированном release commit и
   опубликовать GitHub Release. Release запускает deployment documentation site. Docs workflow требует exact tag,
   `draft == false`, GitHub API field `immutable == true`, validated staging bundle и успешное Central consumer proof
   для той же version и полного commit SHA, пока эти два workflow artifacts доступны. Отсутствующий, false или
   строковый `immutable` блокирует deployment.
9. Проверить GitHub Pages navigation и generated Java API links из опубликованного сайта.

Если после staging требуется изменить код или документацию release commit, этот deployment не публикуется: нужно
подготовить новый commit и повторить staging. Tag, source jar и опубликованные binaries должны соответствовать одному
состоянию репозитория.

## Ручное восстановление документации

После истечения 90-дневного срока staging/consumer workflow artifacts запустить `Docs Deploy` вручную из `main` и
передать:

- `release-version` — опубликованную SemVer version без `v`;
- `release-commit` — lowercase полный SHA commit, на который указывает tag `v<version>`.

Отдельный job с environment `release-recovery` проверяет strict format trust roots и обоих inputs, делает checkout exact
SHA, требует, чтобы tag `v<version>` разрешался в
этот commit, и через GitHub API требует соответствующий non-draft Release с exact tag и boolean `immutable: true`.
Одного существования tag недостаточно. Затем с canonical Maven Central под одним bounded monotonic deadline скачивается
тот же закрытый набор из 90 файлов для `procwright`, `procwright-integrations` и `procwright-kotlin`. Для каждого
артефакта проверяются exact HTTPS URL, status, content type, identity encoding, declared length и size bound. Проверка
также требует:

- полного набора primary, sources и Javadoc JAR, POM, Gradle module metadata, `.asc` и четырех checksum sidecars для
  каждого base artifact без неизвестных classifiers или extensions;
- совпадения MD5, SHA-1, SHA-256 и SHA-512 sidecars с base artifact, после чего isolated `GNUPGHOME` импортирует ровно
  один approved public key с exact `MAVEN_CENTRAL_RECOVERY_SIGNING_FINGERPRINT` и `gpg` криптографически проверяет detached
  signature каждого из 15 base artifacts;
- exact coordinates, module-specific name/description, Apache-2.0 license, project/SCM/developer metadata, packaging,
  dependencies и отсутствия repository policy в POM;
- exact component, variants, dependencies и file hashes в Gradle module metadata;
- bounded безопасной ZIP-структуры, JPMS/automatic module identity, exports/dependencies, package roots и
  module-specific declarations каждого primary/sources/Javadoc JAR;
- успешной strict-сборки сайта через `publicDocsCheck` из checkout release commit.

Central-hosted checksums и signatures находятся рядом с проверяемыми artifacts, поэтому recovery не является
независимой attestation и не доказывает их тождество исходному staged bundle. После истечения workflow artifacts также
невозможно повторно доказать исторические Portal deployment ID, request `publishingType` или consumer behavior. Эти
доказательства обязательны в первоначальном release-event path; manual recovery доказывает immutable GitHub release
identity и полный наблюдаемый набор Central artifacts только для повторного deployment документации.

В release-event path staging и consumer evidence выбираются не по имени: для каждого producer workflow требуется ровно
один successful `workflow_dispatch` run на `main` с exact workflow ID/path/revision и `head_sha` release commit. Затем
ровно один неистекший artifact связывается с run, immutable artifact ID и service SHA-256; raw ZIP скачивается по ID,
сверяется с digest и проверяется как полный staging evidence либо canonical consumer provenance. Pages build отдельно
передает upload output `artifact_id` и SHA-256 канонического дерева. Deploy job с `actions: read`, `contents: read`,
`pages: write` и `id-token: write` делает sparse checkout trusted verifier scripts, скачивает raw current-run artifact
по этому ID, проверяет API digest, exact однофайловый ZIP,
`artifact.tar` и sealed content до вызова `deploy-pages`.
