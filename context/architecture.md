# Архитектура Procwright

## Цель

Procwright — JVM-библиотека для безопасного запуска внешних CLI-программ и управления долгоживущими процессами из
Java и Kotlin. Пользователь выбирает сценарий, а библиотека владеет process lifecycle, ограничением памяти,
конкурентным I/O, timeout, shutdown и диагностикой.

Новая возможность входит в ядро только тогда, когда у нее есть:

- понятный пользовательский сценарий;
- единственный владелец каждого нового инварианта;
- typed failure contract;
- проверка наблюдаемого поведения.

## Пользовательская модель

Единственная корневая точка входа — `Procwright.command(...)`. Она возвращает `CommandService`, связанный с одной
immutable базовой командой. Затем пользователь выбирает сценарий:

- `run()` — конечная команда с `CommandResult`;
- `interactive()` — raw process session;
- `lineSession()` — line-oriented request/response;
- `protocolSession(adapterFactory)` — typed request/response с пользовательским framing;
- `listen()` — потоковая обработка stdout/stderr;
- `lineSession().pooled()` и `protocolSession(adapterFactory).pooled()` — пулы дорогих workers.

Каждый scenario method возвращает immutable persistent `Draft`. Методы `with*` создают новый Draft и не меняют
исходный. Процесс появляется только в явном terminal method: `execute()` или `open()`. `Session.expect()` также
возвращает `Expect.Draft`; helper создается только через `open()`.

Protocol session принимает `Supplier<? extends ProtocolAdapter<I, O>>`. Для каждого `open()` и каждого pool worker
до запуска процесса создается отдельный adapter. Concurrent terminal calls могут вызывать factory конкурентно, поэтому
она должна быть thread-safe и каждый раз возвращать новый non-null adapter.

## Слои

```text
Public API
  Procwright -> CommandService -> scenario Draft -> execute/open
  CommandSpec, policies, results, session handles, exceptions

Scenario normalization
  immutable scenario-specific settings
  validation and construction of execution/session plans
  readiness and pool configuration

Runtime
  process launch and transport selection
  concurrent stdout/stderr draining
  request serialization and protocol decoding
  bounded capture, transcript and queues
  timeout, shutdown and process-tree cleanup
  diagnostics and metrics

Transport
  pipes
  optional PTY provider

Optional layers
  Kotlin Draft extensions and coroutines
  protocol/JSON integrations over the same core runtime
```

Public Draft не является доменной моделью runtime. Он принимает и немедленно валидирует один пользовательский шаг,
копирует mutable input и сохраняет immutable settings. Terminal method передает snapshot во внутренний runtime.

## Владение ответственностью

- `CommandSpec` владеет базовой командой: executable, args, working directory, environment и shell mode.
- Scenario `Draft` владеет допустимым для выбранного workflow набором настроек и его persistent semantics.
- Policy/value objects владеют локальными ограничениями значений.
- Internal settings владеют нормализованным snapshot до запуска.
- Execution/session plan владеет согласованностью комбинаций, которые зависят от нескольких настроек.
- Stateful runtime component владеет lifecycle и concurrency-инвариантами открытого процесса.
- Public result или scenario-specific exception владеет наблюдаемым outcome.

Runtime не восстанавливает пользовательское намерение из случайного набора flags и не принимает public configuration
carriers вне scenario Draft.

## Пакетные границы ядра

- `io.github.ulviar.procwright` — entry point и scenario namespaces с `Draft`/`PoolDraft`.
- `io.github.ulviar.procwright.command` — command model, result, policies и command failures.
- `io.github.ulviar.procwright.session` — sealed session handles, protocol contracts, transcripts, metrics и failures.
- `io.github.ulviar.procwright.diagnostics` — пользовательские events и hooks.
- `io.github.ulviar.procwright.terminal` — terminal policy, request, size, signal и provider SPI.
- `io.github.ulviar.procwright.preset` — чистые преобразования конкретных Draft.
- `io.github.ulviar.procwright.internal` и `.internal.session` — settings, plans и stateful runtime; JPMS их не
  экспортирует.

Session-family contracts находятся в одном public package, потому что разделяют lifecycle и exclusive output
ownership. Их реализации скрыты в `internal.session`. Public sealed handles являются библиотечными контрактами, а не
SPI для пользовательских реализаций.

## Модули

- root project `:` (artifact `procwright`) — Java core без runtime-зависимости на стороннюю process-библиотеку.
- `:procwright-kotlin` — optional Kotlin extensions над теми же Java Draft и handles: Kotlin durations, coroutine
  terminals, `openFlow()` и `protocolAdapterFactory()`.
- `:procwright-integrations` — optional JSON/framing helpers, использующие core runtime.
- `:procwright-test-cli` — управляемый fixture для integration/stress behavior.
- consumer modules — компилируемые внешние примеры и проверка metadata.

Kotlin и integrations не создают второй process engine. Ядро и публичные optional modules не зависят от сторонней
process-библиотеки.

## Границы продукта

В ядро не входят raw session pooling, public leases, stateful affinity, общий async Java API, logging framework,
dependency-specific process API и отдельный runner для каждого частного случая. Такая возможность требует нового
сценария только если ее lifecycle или invariant set нельзя выразить существующим Draft и policy objects.

См. также:

- [scenario-api.md](scenario-api.md) — форма API;
- [scenario-contracts.md](scenario-contracts.md) — наблюдаемые гарантии;
- [invariant-architecture.md](invariant-architecture.md) — владельцы инвариантов;
- [decisions/ADR-0014-package-architecture.md](decisions/ADR-0014-package-architecture.md) — package boundary;
- [decisions/ADR-0015-jpms-encapsulation.md](decisions/ADR-0015-jpms-encapsulation.md) — JPMS boundary.
