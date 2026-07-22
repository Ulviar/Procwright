# ADR-0015: JPMS-инкапсуляция ядра

## Статус

Принято.

## Контекст

JPMS должен быть настоящей границей между public API и implementation details: модуль экспортирует только
пользовательские пакеты, а `io.github.ulviar.procwright.internal` и вложенные runtime-пакеты остаются закрытыми.

`module-info.java` нельзя добавлять как формальность. JPMS экспортирует пакеты целиком, а не отдельные классы. Если в
экспортируемом пакете остается `public` runtime-only support, такой класс становится модульным API независимо от того,
исключен ли он из Javadoc.

Public handles должны описывать контракт, а stateful runtime — оставаться скрытым. Иначе package export превращает
implementation helpers в пользовательский API.

## Решение

Разделить session-family handles на public contracts и internal implementations:

- `io.github.ulviar.procwright.session` содержит публичные sealed handle interfaces и immutable value/exception types;
- `io.github.ulviar.procwright.internal.session` содержит stateful implementations и runtime factories:
  `DefaultSession`, `DefaultExpect`, `DefaultLineSession`, `DefaultStreamSession`, `DefaultPooledLineSession`,
  `SessionRuntime`, `StreamRuntime`, `SessionScenarioSupport`;
- root facade `CommandService` продолжает возвращать scenario-first public interfaces;
- `module-info.java` экспортирует только public API packages:
  `io.github.ulviar.procwright`, `command`, `diagnostics`, `session`, `terminal`;
- `internal` и `internal.session` не экспортируются.

Diagnostics dispatcher и diagnostic schema validator остаются в `io.github.ulviar.procwright.internal`; публичный
diagnostics package описывает только пользовательские hooks и events.

## Последствия

Плюсы:

- core artifact стал именованным Java module `io.github.ulviar.procwright`;
- JPMS теперь действительно скрывает runtime implementation packages;
- публичная session surface описывает сценарные контракты, а не concrete lifecycle owners;
- future implementation changes внутри session runtime не становятся public API по имени класса;
- package boundary tests проверяют не только список пакетов, но и направление production-зависимостей;
- sealed session-family interfaces делают non-SPI nature compile-time свойством, а не только Javadoc предупреждением.

Ограничение: публичные failure constructors нужны internal implementations из другого package. `Session.expect()`
создает public `Expect.Draft`, не раскрывая implementation type в сигнатуре.

## Проверка

- `PublicApiSurfaceTest` проверяет public API packages, отсутствие `internal` в public signatures и exports
  `module-info.class`.
- `PackageBoundaryTest` проверяет production-зависимости между core packages по скомпилированным class files.
- `javadoc` исключает все `internal` packages.
