# ADR-0014: Пакетная архитектура ядра

## Статус

Accepted.

## Контекст

Первая реализация Java-ядра выросла в один плоский пакет `com.github.ulviar.icli`. Это мешает читать код,
размывает владельцев инвариантов и делает публичную поверхность похожей на список классов, а не на сценарную модель.

При этом пакетное разбиение не должно ломать философию iCLI:

- пользователь выбирает сценарий, а не низкоуровневый набор флагов;
- инварианты должны иметь одного владельца в коде;
- внутренние runtime-типы не должны становиться частью пользовательского API только из-за удобства реализации;
- API должен оставаться широким по возможностям, но небольшим и осмысленным по точкам входа.

## Решение

Разделить ядро на пакеты по ответственности:

- `com.github.ulviar.icli` — тонкая facade-точка входа, прежде всего `CommandService`;
- `com.github.ulviar.icli.command` — one-shot command model, result model, run invocation, input/output policies и
  command-specific exceptions;
- `com.github.ulviar.icli.diagnostics` — diagnostic events, listeners, transcript sinks и diagnostic options;
- `com.github.ulviar.icli.session` — raw interactive session, expect, line session, protocol session, stream session,
  pooled line session и pooled protocol session, потому что эти сценарии разделяют инвариант единоличного владения
  stdin/stdout/stderr и session-family lifecycle;
- `com.github.ulviar.icli.terminal` — terminal/PTY policy, request, provider, size и signal types;
- `com.github.ulviar.icli.preset` — scenario presets как пользовательский слой выбора готовых профилей;
- `com.github.ulviar.icli.internal` допускается только для implementation details, которые не должны появляться в
  публичных сигнатурах.
- `com.github.ulviar.icli.internal.session` содержит stateful реализации session-family сценариев и runtime factories,
  которые не экспортируются JPMS-модулем.

Diagnostics runtime dispatcher и schema validator относятся к `internal`: публичный diagnostics package описывает
только пользовательские hooks, events и options, а не механизм доставки событий.

Публичные contracts session-сценариев сознательно остаются в одном пакете. Разносить `Expect`, `LineSession`,
`ProtocolSession`, `StreamSession`, `Session` и pooled session handles по отдельным публичным подпакетам сейчас вредно:
тогда модель сценариев станет технической навигацией вместо пользовательского workflow. Stateful реализации при этом
вынесены в `internal.session` решением [ADR-0015](ADR-0015-jpms-encapsulation.md).

## Инварианты

- Публичные сигнатуры не должны ссылаться на `com.github.ulviar.icli.internal`.
- Runtime-only классы исключаются из пользовательской документации и не рассматриваются как пользовательский API.
- Направления production-зависимостей между core packages закреплены тестом, а не только описаны в ADR.
- Public session-family handles являются sealed interfaces; lifecycle state, output ownership и runtime factories живут в
  неэкспортируемом `internal.session`.
- Если класс владеет инвариантом, зависимые сценарии должны находиться рядом с ним или обращаться через явно
  выделенный runtime boundary.
- Пакетное разбиение не должно превращать scenario-first API в набор технических namespaces.
- Новые сценарии сначала определяют владельца инвариантов, затем выбирают пакет.

## Отклоненные варианты

### Оставить весь публичный API в корневом пакете

Отклонено, потому что корневой пакет уже стал нечитаемым и не показывает архитектурных границ.

### Разнести каждый сценарий в отдельный пакет

Отклонено для текущего состояния. Это выглядело бы аккуратно в Javadoc, но нарушило бы важный runtime-инвариант:
`Session`, `Expect`, `LineSession`, `ProtocolSession` и `StreamSession` должны координировать единоличное владение
stdin/stdout/stderr без раскрытия внутренних методов наружу.

### Перенести runtime в `internal` без разделения contracts и implementations

Отклонено. Простое перемещение runtime без public handle interfaces создавало бы публичные мосты или ломало
session-сценарии. Полный split стал возможен позже через ADR-0015: public package содержит handle contracts, а
stateful реализации и runtime factories живут в `internal.session`.

## Последствия

Плюсы:

- пакетная структура начинает отражать назначение классов;
- корневой пакет остается входной точкой, а не свалкой типов;
- session-инварианты остаются закрытыми за public handle contracts и неэкспортируемым `internal.session`;
- публичная документация и API surface tests получают явную модель разрешенных пакетов.

Минусы:

- это breaking change для импортов до первого релиза;
- public session-family handles не являются SPI; sealed contracts делают это compile-time свойством;
- tests, Kotlin facade, integrations и comparison module должны обновить imports.

## Проверка

- `PublicApiSurfaceTest` фиксирует разрешенные public API packages и запрещает утечки `internal` в public signatures.
- `PackageBoundaryTest` читает production class files и проверяет объявленные направления зависимостей между пакетами.
- `PublicApiSurfaceTest` проверяет `module-info.class` и список экспортируемых packages.
- Javadoc gate не должен публиковать runtime-only implementation details.
- Unit, integration, Kotlin, integrations и public docs checks должны проходить после переноса.
