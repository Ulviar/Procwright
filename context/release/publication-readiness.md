# Готовность к публикации

## Назначение

Документ описывает подготовку первой версии `0.1.0` для Maven Central. Кандидат загружается через Central Portal
в режиме `USER_MANAGED`; состояние `VALIDATED` означает успешную проверку, но не публикацию. Завершение публикации
остаётся отдельным действием. До него public docs не должны обещать доступность версии из Central.

## Что готово сейчас

- `procwright`, `procwright-integrations` и `procwright-kotlin` имеют стабильные coordinates и Maven publications;
- все три publication содержат sources, API documentation, лицензию проекта в каждом JAR и обязательные POM metadata; это проверяет
  `publicationStructureCheck` без привязки к remote registry;
- public artifacts собираются с Java 25 target и требуют JVM 25 в Gradle metadata;
- CI публикует все три модуля в изолированный Maven Local repository и запускает внешние Java, Kotlin и integrations
  consumers через Gradle metadata и принудительный Maven POM-only resolution;
- Kotlin consumer `check` исполняет также обе канонические точки входа из документации: `KotlinExampleKt` и
  `KotlinPoolExampleKt`, включая запуск вложенного worker с опубликованными runtime dependencies;
- regression gate проверяет scan deadline/interruption и сохранение scan capacity до фактического возврата операции;
  per-call isolation для trusted PTY provider не обещается; bounded scan и asynchronous destroy fallback сохраняются;
- protocol proofs проверяют bounded chunk decoding и ранний отказ oversized text field, не закрепляя точную byte
  position после terminal failure;
- cleanup proofs проверяют восстановление после временно неполного scan и сохранение известных descendants,
  недоступности observation и overflow; последний combined refresh должен завершиться до phase deadline. Повторные
  observation failures имеют ограниченный retained detail с сохранением primary и interruption;
- capture identity proofs разрешают разные существующие файлы с portable-alias names и сохраняют защиту от одного
  файла через hardlink/symlink; неизвестные targets остаются под консервативной проверкой;
- Public package/scenario surface, Kotlin ABI, документация и cross-platform behavior имеют отдельные gates.

Агрегирующая локальная проверка кандидата:

```bash
./gradlew publicationReadinessCheck -Pprocwright.version=0.1.0
```

Она доказывает согласованность исходников, API и документации. Maven publication metadata отдельно доказывает CI
publication smoke: он публикует все три модуля в изолированный Maven Local repository, а затем запускает normal и
POM-only consumers. Ни одна из этих проверок не доказывает прием артефактов конкретным remote registry.

## Загрузка и проверка кандидата

Для загрузки используется `com.vanniktech.maven.publish.base` 0.37.0 поверх существующих трёх `mavenJava`
publications. Release version передаётся явно. Plugin и GnuPG-подписание включаются только флагом
`procwright.centralPublishing=true`: обычная сборка и `publishToMavenLocal` не требуют ключа или Portal credentials.

В пользовательском `~/.gradle/gradle.properties` должны быть настроены `mavenCentralUsername`,
`mavenCentralPassword` и параметры `signing.gnupg.*`. Gradle вызывает локальный GnuPG через `useGpgCmd()`.
Ключи, пароли и токены не входят в репозиторий или команды ниже.
Если ключ защищён паролем, GnuPG должен иметь доступ к локальному pinentry. Перед запуском из фонового процесса
разблокировать ключ в своём терминале; пароль не передавать через аргументы Gradle или репозиторий.

После `publicationReadinessCheck` и isolated local publication smoke загрузить тот же кандидат:

```bash
./gradlew publishToMavenCentral \
  -Pprocwright.version=0.1.0 \
  -Pprocwright.centralPublishing=true
```

Автоматическая публикация отключена (`automaticRelease = false`). Убедиться, что deployment получил состояние `VALIDATED`;
ошибки Portal нужно исправить и проверить на новом кандидате. Сам факт успешной Gradle-сборки не доказывает приём в Central.

Проверить именно загруженные артефакты через temporary deployment repository. В URL ниже заменить `<deployment-id>`
идентификатором, полученным при загрузке:

```bash
./gradlew \
  :procwright-consumer-examples:test \
  :procwright-integrations-consumer-example:check \
  :procwright-kotlin-consumer-example:check \
  -Pprocwright.consumerVersion=0.1.0 \
  -Pprocwright.consumerRepository="https://central.sonatype.com/api/v1/publisher/deployment/<deployment-id>/download/" \
  --dependency-verification=off \
  --refresh-dependencies --rerun-tasks --no-daemon
```

Повторить команду с дополнительным `-Pprocwright.consumerPomOnly=true`, чтобы проверить Maven POM без Gradle metadata.
Проверка dependency verification отключается для smoke, потому что собственный новый кандидат ещё не внесён в
`verification-metadata.xml`, как и при isolated local publication smoke в CI. Обычный build сохраняет dependency verification.
Repository credentials берутся из тех же пользовательских Central properties и передаются только endpoint загрузки
deployment на `central.sonatype.com`; локальные или другие consumer repositories их не получают.

Все три consumers должны компилироваться и исполняться в обоих режимах. Deployment ID — результат конкретной загрузки,
его не нужно сохранять в контекстных документах. Отдельный release workflow или собственный Portal client для этого пути
не требуется.

## Завершение публикации

Подготовка до `VALIDATED` и staging smoke не завершает release. Для публикации нужны:

1. зелёный `publicationReadinessCheck`, local/staging consumers и полный cross-platform CI для одного release commit;
2. отдельное подтверждение публикации проверенного deployment в Central Portal и состояние `PUBLISHED`;
3. повтор consumer smoke для `0.1.0` без `procwright.consumerRepository`, в normal и POM-only режимах: зависимости
   должны разрешиться непосредственно из Maven Central;
4. удаление пометки о недоступности candidate из README и public installation/compatibility/Kotlin docs;
5. tag и release для проверенного commit после доступности всех трёх артефактов.

Если артефакты изменились после валидации, сначала собрать и проверить новый кандидат. Не публиковать прежний deployment
под видом обновлённого кода.

## После первого release

До первого изменения public API после `0.1.0` нужно подключить описанный в
[compatibility-policy.md](compatibility-policy.md#стабильность-публичного-api) `javaBinaryCompatibilityCheck` к
опубликованным core/integrations coordinates и зафиксировать Kotlin ABI release baseline. Это обязательный bootstrap
следующего цикла разработки, а не условие публикации версии, с которой ещё нечего сравнивать.
