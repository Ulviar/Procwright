# ADR-0015: JPMS-инкапсуляция ядра

## Статус

Accepted.

## Контекст

JPMS должен быть настоящей границей между public API и implementation details: модуль экспортирует только
пользовательские пакеты, а `com.github.ulviar.icli.internal` и вложенные runtime-пакеты остаются закрытыми.

`module-info.java` нельзя добавлять как формальность. JPMS экспортирует пакеты целиком, а не отдельные классы. Если в
экспортируемом пакете остается `public` runtime-only support, такой класс становится модульным API независимо от того,
исключен ли он из Javadoc.

Предыдущее состояние показало архитектурный симптом: stateful handle-классы `Session`, `Expect`, `LineSession`,
`StreamSession` и `PooledLineSession` одновременно были пользовательским API и владельцами runtime state. Из-за этого
`SessionRuntime`, `StreamRuntime` и `SessionScenarioSupport` приходилось держать публичными в `session` package как
мосты к package-private state.

## Решение

Разделить session-family handles на public contracts и internal implementations:

- `com.github.ulviar.icli.session` содержит публичные interfaces и immutable value/option/exception types;
- `com.github.ulviar.icli.internal.session` содержит stateful implementations и runtime factories:
  `DefaultSession`, `DefaultExpect`, `DefaultLineSession`, `DefaultStreamSession`, `DefaultPooledLineSession`,
  `SessionRuntime`, `StreamRuntime`, `SessionScenarioSupport`;
- root facade `CommandService` продолжает возвращать scenario-first public interfaces;
- `module-info.java` экспортирует только public API packages:
  `com.github.ulviar.icli`, `command`, `diagnostics`, `preset`, `session`, `terminal`;
- `internal` и `internal.session` не экспортируются.

Diagnostics dispatcher и diagnostic schema validator остаются в `com.github.ulviar.icli.internal`; публичный
diagnostics package описывает только пользовательские hooks, events и options.

## Последствия

Плюсы:

- core artifact стал именованным Java module `com.github.ulviar.icli`;
- JPMS теперь действительно скрывает runtime implementation packages;
- публичная session surface описывает сценарные контракты, а не concrete lifecycle owners;
- future implementation changes внутри session runtime не становятся public API по имени класса;
- package boundary tests проверяют не только список пакетов, но и направление production-зависимостей.

Минусы:

- это pre-release breaking change для пользователей, которые могли опираться на concrete classes или reflection;
- публичные failure types получили публичные constructors, потому что internal implementations теперь создают их из
  другого package;
- `Expect.on(Session, ...)` остается public static factory на interface и поэтому public `session` package зависит от
  internal implementation package на уровне bytecode. Это не leak в сигнатурах и закрыто JPMS exports, но это осознанная
  внутренняя связь ради сохранения прежней пользовательской API-идеи.

## Проверка

- `PublicApiSurfaceTest` проверяет public API packages, отсутствие `internal` в public signatures и exports
  `module-info.class`.
- `PackageBoundaryTest` проверяет production-зависимости между core packages по скомпилированным class files.
- `javadoc` исключает все `internal` packages.
