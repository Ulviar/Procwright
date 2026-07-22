# ADR-0017: Стратегия публикации release artifacts

## Статус

Принято.

## Контекст

Проект подготовил release hardening baseline: group/version, source/Javadoc artifacts для Java modules, public docs,
license, dependency review и release checklist. Для использования Procwright как внешней зависимости нужен реальный publishing
setup, но credentials и signing material не должны попадать в репозиторий.

## Решение

Release setup публикует Java 17-targeted artifacts с координатами:

- `io.github.ulviar:procwright`;
- `io.github.ulviar:procwright-kotlin`;
- `io.github.ulviar:procwright-integrations`.

Текущий configured target — Maven Central через Central Portal:

- `maven-publish` и `signing` включены для public artifacts;
- POM metadata содержит name, description, URL, Apache-2.0 license, SCM и developers;
- Central Portal credentials поступают в privileged wrapper через `CENTRAL_USERNAME`/`CENTRAL_PASSWORD`, переносятся в
  owner-only temporary file и удаляются из environment до запуска Python client; они не попадают в argv или logs;
- signing key читается только из environment-level secrets `SIGNING_KEY`/`SIGNING_PASSWORD`; его primary fingerprint
  должен совпасть с environment-level variable `MAVEN_CENTRAL_STAGING_SIGNING_FINGERPRINT` до подписания;
- publish tasks fail fast, если `procwright.javaRelease != 17`.
- remote publish tasks fail fast, если version остается `*-SNAPSHOT` или не является canonical SemVer; один parser
  отклоняет leading zero в numeric identifiers, пустые prerelease/build identifiers и version с leading `v` во всех
  release workflows и verification scripts. До checkout inline-проверка принимает только lowercase полный commit SHA;
  version проверяется уже кодом из exact checkout.
- `mavenCentralBundle` собирает signed repository bundle с checksums без добавления runtime dependencies. Trusted
  verifier требует exact 90-файловый набор, проверяет checksum values, структуру signature armor и semantics
  JAR/POM/module metadata; Central Portal выполняет криптографическую validation подписей.
- manual staging workflow допускает только текущий trusted `main` commit, связанный с успешным CI `push` run того же
  workflow revision. Target build выполняется без release secrets и создает immutable handoff. Отдельный свежий runner
  с environment `maven-central` проверяет handoff trusted code, подписывает и загружает тот же открытый и проверенный bundle
  как `USER_MANAGED` deployment. После `VALIDATED` сохраняются bundle, SHA-256, deterministic manifest, provenance и
  нормализованное deployment evidence; target code на privileged runner не выполняется. Финальная кнопка Publish
  остается ручным шагом в Portal для первого release.
- отдельный consumer workflow для exact version/SHA ждет состояние `PUBLISHED`, byte-for-byte сравнивает со staged
  bundle все 90 опубликованных base/signature/checksum files и выполняет external consumers через Gradle module metadata
  и Maven POM-only path; успешный workflow сохраняет publication proof.
- release-triggered deployment документации требует GitHub Release с exact tag, `draft == false` и API field
  `immutable == true`, проверяет разрешение tag в тот же commit и требует неистекшие artifacts успешного staging и
  publication consumer workflows для exact version/SHA. Каждый producer выбирается как единственный successful
  `workflow_dispatch` run trusted workflow ID/path на `main` с exact `head_sha`; evidence связывается с immutable
  artifact ID, service digest и скачанными raw bytes, а content проходит staging/canonical consumer verification.
  Manual recovery после их 90-дневного retention выполняется отдельным job с environment `release-recovery` и требует ту же
  immutable release identity и с canonical Maven Central заново проверяет полный закрытый 90-файловый набор:
  base artifacts, signatures, checksums и JAR/POM/module semantics. Environment-level variables
  `MAVEN_CENTRAL_RECOVERY_SIGNING_FINGERPRINT` и `MAVEN_CENTRAL_RECOVERY_SIGNING_PUBLIC_KEY` задают explicit trust root:
  isolated GPG keyring должен содержать ровно этот approved primary key, и detached signature каждого base artifact обязана
  криптографически пройти проверку. Co-located Central evidence не является независимой
  attestation и не восстанавливает identity исходного staged bundle, Portal request/deployment или consumer behavior;
  recovery достаточен только для повторного deployment docs, а не вместо первоначальных staging/consumer evidence.
- Pages сохраняет двух-job least-privilege model. Build передает upload output artifact ID и digest канонического
  content tree; deploy получает `actions: read`, `contents: read`, `pages: write` и `id-token: write`, через sparse
  checkout загружает только trusted verifier scripts и повторно связывает exact current run, artifact ID, API digest,
  raw ZIP, единственный `artifact.tar` и его content. `deploy-pages` по контракту платформы
  принимает только artifact name, поэтому verifier доказывает, что в current run этому неизбежному имени соответствует
  ровно один уже проверенный immutable artifact.
- Critical workflow `run` values ссылаются на fixed repository scripts или сравниваются validator-ом как exact parsed
  YAML scalar. Structured YAML check проверяет pinned action SHA, exact root/job permissions и монотонный порядок
  release steps; `bash -n` отдельно проверяет syntax fixed scripts. Реальное выполнение workflow остается
  behavioral proof.
- Staging использует `ubuntu-24.04`, Temurin 17.0.19+10, Python 3.13.14 и uv 0.10.12; сохраненный workflow artifact
  содержит effective toolchain/runner provenance рядом с bundle и SHA-256.

## Инварианты

- Publishing setup не должен добавлять runtime dependency в public artifacts.
- Credentials не хранятся в репозитории.
- Published artifacts собираются с Java 17 target; Java 21/25 остаются checked source variants.
- Published artifacts должны соответствовать JPMS/package boundary tests.
- Source и Javadoc artifacts обязательны для Java modules.
- Kotlin artifact остается optional и не становится dependency core module.
- Docs deployment fail-closed, если GitHub Release API не возвращает boolean `immutable: true`; существование tag само
  по себе не считается доказательством immutability.
- Secrets и trust-root variables существуют только на уровне environments: `maven-central` хранит Central/signing
  secrets и staging fingerprint, `release-recovery` хранит recovery fingerprint/public key. Repository-level copies и
  прежние общие names запрещены, чтобы удаление protected value не включало GitHub scope fallback.
- Environments `maven-central` и `release-recovery` требуют минимум одного независимого reviewer, включенный
  `Prevent self-review` и deployment branch policy `Selected branches and tags` только для `main`. Оба workflow jobs
  дополнительно проверяют `refs/heads/main` и trusted workflow SHA.

## Последствия

Плюсы:

- Procwright можно готовить как внешнюю dependency с устойчивыми coordinates.
- Artifact policy явно фиксирует Java 17 minimum target.
- Maven Central publication не требует хранения secrets в repo.
- Публикационный слой остается отдельным от public API и process runtime.

Минусы:

- Первый upload невозможен без verified namespace `io.github.ulviar` в Central Portal.
- Первый Central deployment требует environment-level secrets `CENTRAL_USERNAME`, `CENTRAL_PASSWORD`, `SIGNING_KEY` и
  `SIGNING_PASSWORD`, staging fingerprint variable и независимого reviewer, который не запускал deployment.
