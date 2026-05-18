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
- `com.github.ulviar.icli.session` — raw interactive session, expect, line session, stream session и pooled line
  session, потому что эти сценарии разделяют один инвариант владения stdout/stderr;
- `com.github.ulviar.icli.terminal` — terminal/PTY policy, request, provider, size и signal types;
- `com.github.ulviar.icli.preset` — scenario presets как пользовательский слой выбора готовых профилей;
- `com.github.ulviar.icli.internal` допускается только для implementation details, которые не должны появляться в
  публичных сигнатурах.

Session-сценарии сознательно остаются в одном пакете. Разносить `Expect`, `LineSession`, `StreamSession` и `Session`
по отдельным подпакетам сейчас вредно: тогда пришлось бы раскрывать методы владения выводом как публичный API или
дублировать lifecycle-инварианты.

## Инварианты

- Публичные сигнатуры не должны ссылаться на `com.github.ulviar.icli.internal`.
- Runtime-only классы исключаются из пользовательской документации и не рассматриваются как пользовательский API.
- Если класс владеет инвариантом, зависимые сценарии должны находиться рядом с ним или обращаться через явно
  выделенный runtime boundary.
- Пакетное разбиение не должно превращать scenario-first API в набор технических namespaces.
- Новые сценарии сначала определяют владельца инвариантов, затем выбирают пакет.

## Отклоненные варианты

### Оставить весь публичный API в корневом пакете

Отклонено, потому что корневой пакет уже стал нечитаемым и не показывает архитектурных границ.

### Разнести каждый сценарий в отдельный пакет

Отклонено для текущего состояния. Это выглядело бы аккуратно в Javadoc, но нарушило бы важный runtime-инвариант:
`Session`, `Expect`, `LineSession` и `StreamSession` должны координировать единоличное владение stdout/stderr без
раскрытия внутренних методов наружу.

### Полностью скрыть runtime через отдельный `internal` пакет

Отклонено как единственное правило. Java package-private доступ не позволяет так сделать без публичных мостов там, где
runtime должен создавать или обслуживать package-private состояние сценария. `internal` используется только там, где
это не ухудшает инкапсуляцию сценария.

## Последствия

Плюсы:

- пакетная структура начинает отражать назначение классов;
- корневой пакет остается входной точкой, а не свалкой типов;
- session-инварианты остаются закрытыми внутри одного пакета;
- публичная документация и API surface tests получают явную модель разрешенных пакетов.

Минусы:

- это breaking change для импортов до первого релиза;
- часть runtime классов может оставаться package-private рядом со scenario classes, а не в универсальном `internal`;
- tests, Kotlin facade, integrations и comparison module должны обновить imports.

## Проверка

- `PublicApiSurfaceTest` фиксирует разрешенные public API packages и запрещает утечки `internal` в public signatures.
- Javadoc gate не должен публиковать runtime-only implementation details.
- Unit, integration, Kotlin, integrations и public docs checks должны проходить после переноса.
