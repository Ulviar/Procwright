# Публикация релиза

Текущая версия `0.1.0` всех трёх модулей доступна из Maven Central. Для следующего релиза выбрать новую версию по
[политике версий](versioning-policy.md); опубликованные coordinates нельзя использовать повторно.

## Подготовка

Для одного release commit должны пройти:

- `publicationReadinessCheck`: runtime regression/stress, public API boundary, документация и структура publications;
- cross-platform CI на Linux, macOS и Windows;
- CI publication smoke: все три модуля публикуются в изолированный Maven Local repository, затем Java, Kotlin и
  integrations consumers компилируются и исполняются через Gradle metadata и отдельно через Maven POM-only resolution.

`publicationStructureCheck` проверяет sources, API documentation, лицензию в каждом JAR, POM metadata и Java 25
bytecode/Gradle variants. Kotlin consumer исполняет также канонические `KotlinExampleKt` и `KotlinPoolExampleKt`.
Runtime guarantees и их proofs находятся в [карте инвариантов](../quality/invariant-proof-map.md).
Локальные проверки и CI не заменяют проверку загруженных в Central артефактов.

В командах ниже заменить `<release-version>` выбранной новой версией:

```bash
procwright_release_version='<release-version>'
./gradlew publicationReadinessCheck "-Pprocwright.version=$procwright_release_version"
```

До первого изменения public API после `0.1.0` обязателен bootstrap из
[политики совместимости](compatibility-policy.md#стабильность-публичного-api): подключить
`javaBinaryCompatibilityCheck` к опубликованным core/integrations coordinates и зафиксировать Kotlin ABI release baseline.
Java binary gate пока не подключён; до выполнения bootstrap public API не меняется.

## Загрузка и проверка кандидата

Для загрузки используется `com.vanniktech.maven.publish.base` поверх существующих трёх `mavenJava` publications.
Plugin и GnuPG-подписание включаются только флагом `procwright.centralPublishing=true`: обычная сборка и
`publishToMavenLocal` не требуют ключа или Portal credentials.

В пользовательском `~/.gradle/gradle.properties` настроить `mavenCentralUsername`, `mavenCentralPassword` и параметры
`signing.gnupg.*`. Gradle вызывает локальный GnuPG через `useGpgCmd()`. Публичный ключ должен быть доступен на
[поддерживаемом Central keyserver](https://central.sonatype.org/publish/requirements/gpg/#distributing-your-public-key).
Ключи, пароли и токены не входят в репозиторий или команды ниже. Если ключ защищён паролем, перед фоновой сборкой
разблокировать его через локальный pinentry в своём терминале; пароль не передавать в аргументах Gradle.

После локальных проверок и CI загрузить тот же кандидат:

```bash
./gradlew publishToMavenCentral \
  "-Pprocwright.version=$procwright_release_version" \
  -Pprocwright.centralPublishing=true
```

Автоматическая публикация отключена (`automaticRelease = false`). Deployment создаётся в режиме `USER_MANAGED`.
Дождаться состояния `VALIDATED`; оно означает успешную проверку Central, но ещё не публикацию. Ошибки Portal
исправить и проверить на новом кандидате.

Проверить загруженные артефакты через temporary deployment repository. В URL заменить `<deployment-id>`
идентификатором, полученным при загрузке:

```bash
./gradlew \
  :procwright-consumer-examples:test \
  :procwright-integrations-consumer-example:check \
  :procwright-kotlin-consumer-example:check \
  "-Pprocwright.consumerVersion=$procwright_release_version" \
  -Pprocwright.consumerRepository="https://central.sonatype.com/api/v1/publisher/deployment/<deployment-id>/download/" \
  --dependency-verification=off \
  --refresh-dependencies --rerun-tasks --no-daemon
```

Повторить с `-Pprocwright.consumerPomOnly=true`, чтобы проверить Maven POM без Gradle metadata. Все три consumers
должны компилироваться и исполняться в обоих режимах. Dependency verification отключается только для smoke:
собственные новые артефакты ещё не внесены в `verification-metadata.xml`. Обычная сборка сохраняет эту проверку.

Repository credentials берутся из пользовательских Central properties и передаются только endpoint загрузки
deployment на `central.sonatype.com`; локальные или другие consumer repositories их не получают.
Deployment ID относится к конкретной загрузке и не сохраняется в контекстных документах.

## Завершение публикации

1. После подтверждения публикации выпустить проверенный deployment в Central Portal и дождаться `PUBLISHED`.
2. Повторить consumer smoke выше без `procwright.consumerRepository`, в normal и POM-only режимах. Все три зависимости
   должны разрешиться непосредственно из Maven Central.
3. Обновить версии в README и public installation/compatibility/Kotlin docs после подтверждения доступности артефактов.
4. Создать tag и GitHub Release для проверенного release commit; описать пользовательские изменения и breaking changes.

Если артефакты изменились после валидации, сначала собрать и проверить новый кандидат. Не публиковать прежний deployment
под видом обновлённого кода. После `PUBLISHED` исправления требуют новой версии.
