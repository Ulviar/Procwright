# ADR-0012: Scenario-first API после сравнения process-библиотек

## Статус

Accepted.

## Контекст

Модуль `:procwright-comparison` сравнил Procwright с JDK `ProcessBuilder`, Apache Commons Exec, ZeroTurnaround zt-exec,
NuProcess, ExpectIt и Pty4J по сценариям, которые соответствуют целевой пользовательской модели Procwright:

- one-shot command execution;
- non-zero diagnostics;
- stdin/environment setup;
- bounded large stdout/stderr capture;
- timeout churn;
- streaming callbacks;
- line request-response session;
- expect/prompt automation;
- terminal-required PTY;
- warm worker pool;
- structured command-backed tool observation.

Результат comparison harness подтвердил исходную гипотезу: простые one-shot сценарии закрываются разными библиотеками,
но реальные CLI-workflow быстро требуют набора инвариантов, которые не должны вручную собираться пользователем из
низкоуровневых stream/process primitives.

## Решение

Procwright остается scenario-first библиотекой, а не thin wrapper над `ProcessBuilder` или фасадом над одной внешней
process-библиотекой.

Публичная модель:

- пользователь сначала выбирает канонический сценарий (`run`, `lineSession`, `interactive`, `expect`, `listen`,
  `pooled`);
- сценарий предоставляет маленький typed builder с доменными policies;
- scenario builder/draft нормализуется resolver/domain layer в immutable invocation/execution plan;
- runtime получает только валидированный plan и исполняет lifecycle/shutdown/I/O инварианты;
- инварианты исполняются внутри библиотеки: timeout, cancellation, bounded output, stream draining, cleanup,
  diagnostics, lifecycle и structured result;
- backend-specific details не протекают в public API.

Внешние библиотеки трактуются так:

- JDK `ProcessBuilder` — надежный baseline и portability floor, но не пользовательская API-модель.
- Apache Commons Exec — источник идей для watchdog/timeouts и diagnostic maturity.
- ZeroTurnaround zt-exec — источник идей для краткости fluent one-shot experience.
- NuProcess — возможный источник идей или optional backend для неблокирующего I/O, но не public dependency core.
- ExpectIt — специализированный pattern для expect/prompt automation.
- Pty4J — специализированный PTY transport, который может жить только за optional capability boundary.

## Инварианты

- Новый public API должен добавлять или усиливать сценарий, а не только открывать новый низкоуровневый flag.
- Нельзя переносить dependency-specific types в `io.github.ulviar.procwright`.
- Optional backend может появиться только за narrow port/SPI и должен возвращать те же public results.
- Если возможность требует внешнего transport, core API описывает capability и structured unavailable result, а не
  конкретную библиотеку.
- PTY остается terminal capability/transport boundary внутри `interactive`/`lineSession`, а не отдельной core
  scenario family.
- Command-backed tool остается optional integration layer поверх process scenarios, а не частью core process workflow.
- Comparison harness остается исследовательским модулем и не является runtime dependency core.
- Performance conclusions из comparison harness считаются workflow signals, а не JMH-grade benchmark.

## Отклоненные варианты

### Сделать Procwright thin wrapper над `ProcessBuilder`

Отклонено, потому что пользователь снова будет владеть state machine, stream draining, timeout cleanup, session
interaction и diagnostics. Это прямо противоречит цели изоляции инвариантов.

### Выбрать одну внешнюю process-библиотеку как ядро публичного API

Отклонено, потому что каждая библиотека сильна в своем scope, но ни одна не задает полный scenario-first contract Procwright.
Такой выбор привязал бы публичную модель к чужим abstractions и усложнил бы future backend changes.

### Экспортировать несколько backend-specific API

Отклонено, потому что это превратило бы Procwright в каталог adapters и размывало бы единый пользовательский язык сценариев.

## Последствия

Плюсы:

- Procwright сохраняет собственную архитектурную идентичность.
- Public API остается стабильнее backend-решений.
- Сценарии можно усиливать независимо от transport implementation.
- Comparison module становится постоянным regression signal для API philosophy.

Минусы:

- Библиотека берет на себя больше runtime-ответственности, чем thin wrapper.
- Для некоторых specialized transports нужны optional modules или SPI.
- Нужно постоянно поддерживать контракты сценариев, чтобы широкие возможности не превратились в набор flags.

## Проверка

- `:procwright-comparison:comparisonReport` регулярно проверяет scenario coverage внешних библиотек.
- `:procwright-comparison:comparisonCheck` выполняет non-mutating research gate и пишет отчет в `build/`.
- Raw comparison reports не хранятся в `context/`; долгоживущий вывод фиксируется этим ADR и проверяемыми границами.
- Public API surface tests должны предотвращать протекание backend-specific типов в core top-level packages и public
  signatures.
- Release checklist должен содержать gate против расширений, которые ломают scenario-first модель.
