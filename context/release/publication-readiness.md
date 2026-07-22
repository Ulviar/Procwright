# Готовность к публикации

## Назначение

Документ фиксирует только то, что полезно до выбора первой публичной версии. Procwright пока не имеет публичного
release, поэтому способ загрузки в registry, signing и release automation намеренно не закреплены. Их нужно выбрать по
актуальным требованиям площадки непосредственно перед публикацией.

## Что готово сейчас

- `procwright`, `procwright-integrations` и `procwright-kotlin` имеют стабильные coordinates и Maven publications;
- все три publication содержат sources, API documentation и обязательные POM metadata; это проверяет
  `publicationStructureCheck` без привязки к remote registry;
- public artifacts собираются с Java 17 target;
- CI публикует все три модуля в изолированный Maven Local repository и запускает внешние Java, Kotlin и integrations
  consumers через Gradle metadata и принудительный Maven POM-only resolution;
- API signatures, Kotlin ABI, документация и cross-platform behavior имеют отдельные gates.

Агрегирующая локальная проверка:

```bash
./gradlew publicationReadinessCheck --project-prop=procwright.javaRelease=17
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
