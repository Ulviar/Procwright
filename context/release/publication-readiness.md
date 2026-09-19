# Готовность к публикации

## Назначение

Документ фиксирует только то, что полезно до выбора первой публичной версии. Procwright пока не имеет публичного
release, поэтому способ загрузки в registry, signing и release automation намеренно не закреплены. Их нужно выбрать по
актуальным требованиям площадки непосредственно перед публикацией.

## Что готово сейчас

- `procwright`, `procwright-integrations` и `procwright-kotlin` имеют стабильные coordinates и Maven publications;
- все три publication содержат sources, API documentation и обязательные POM metadata; это проверяет
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
- Public package/scenario surface, Kotlin ABI, документация и cross-platform behavior имеют отдельные gates.

Агрегирующая локальная проверка:

```bash
./gradlew publicationReadinessCheck
```

Она доказывает согласованность исходников, API и документации. Maven publication metadata отдельно доказывает CI
publication smoke: он публикует все три модуля в изолированный Maven Local repository, а затем запускает normal и
POM-only consumers. Ни одна из этих проверок не доказывает прием артефактов конкретным remote registry.

## Что выбирается перед первым release

Перед публикацией нужно принять только решения, которые зависят от реального release path:

1. выбрать registry и его поддерживаемый способ загрузки;
2. настроить требуемое registry подписание и хранение credentials;
3. выполнить publication smoke против staging или опубликованной версии;
4. прогнать `publicationReadinessCheck` и полный CI на одном release commit;
5. создать tag и release только после успешной публикации артефактов.

Не нужно заранее строить собственные Portal clients, provenance protocol, recovery workflow или signing framework.
Если выбранная площадка предоставляет стандартный Gradle plugin или action, сначала используется он; custom tooling
добавляется только для конкретного непокрытого инварианта.

## После первого release

До первого изменения public API после `0.1.0` нужно подключить описанный в
[compatibility-policy.md](compatibility-policy.md#стабильность-публичного-api) `javaBinaryCompatibilityCheck` к
опубликованным core/integrations coordinates и зафиксировать Kotlin ABI release baseline. Это обязательный bootstrap
следующего цикла разработки, а не условие публикации версии, с которой ещё нечего сравнивать.
