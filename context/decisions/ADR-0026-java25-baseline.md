# ADR-0026: Единая Java 25 и актуальные стабильные инструменты

## Статус

Принято. Заменяет удалённое ADR-0020; прежнее решение доступно в Git history.

## Контекст

Совместимость с прежними JVM не является целью проекта. Варианты targets и рефлексивные threading fallbacks
усложняли сборку и runtime без необходимой пользовательской гарантии. До первого публичного релиза можно
согласованно обновить bytecode, optional integration types и инструменты.

## Решение

- Все модули используют JDK 25 toolchain и Java/Kotlin target 25. CI проверяет Java 25 на Linux, macOS и Windows.
  `publicationStructureCheck` проверяет major 69/minor 0 каждого опубликованного class и JVM 25 metadata всех library variants.
- Lifecycle tasks используют прямые virtual-thread API без наследования caller thread-local state. Bounded provider,
  notification и pool workers сохраняют отдельные лимиты и platform threads: зависшая операция удерживает свой slot.
  Автоматический `ExecutorService.close()` не заменяет bounded cleanup, поскольку может ждать бесконечно.
- Notification context принадлежит лексическому `ScopedValue`; при переходе между потоками target передаётся явно.
  Sealed supervision outcomes разбираются исчерпывающим pattern switch. Kotlin future bridge использует стандартный
  coroutines `await()` с проверкой отмены перед completed-future fast path.
- Optional integrations использует Jackson 3 и экспортирует `tools.jackson.databind.JsonNode`. Миграция типов допустима
  до первого релиза; byte budgets, strict UTF-8, duplicate keys, trailing tokens и nesting limits сохраняются.
- Используются стабильные версии tooling с exact pins, SHA-256 verification и action commit SHA. Preview/incubator API
  не входят в baseline. Structured concurrency не заменяет текущих lifecycle owners.
- Records, sealed policies, defensive copies, process/output bounds и OS capability fallbacks сохраняются там, где
  выражают действующий контракт. Новая синтаксическая форма сама по себе не является причиной переписывания.

## Проверка и последствия

Переход доказывают regression/stress, Kotlin ABI и nullness, строгие Java/Kotlin/public docs, внешние consumers с
Gradle metadata и POM-only resolution и Java 25 CI на трёх ОС. System PTY обязателен на Linux/macOS runners.
Точные версии инструментов находятся в build configuration и [dependency review](../release/dependency-review.md).
Пользователи должны перейти на Java 25; JSON integrations используют Jackson 3 API. Дальнейшее упрощение runtime
и пересмотр дорогих гарантий остаются отдельным пунктом [бэклога](../backlog.md).
