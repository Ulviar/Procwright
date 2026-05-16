# Верхнеуровневый план максимальной версии iCLI

## Цель максимальной версии

Максимальная версия iCLI — это scenario-first JVM-библиотека для управления внешними CLI-процессами: от простого
one-shot запуска до интерактивных REPL, prompt automation, streaming monitoring, pooled workers и CLI-backed
интеграций.

Публичный API должен позволять пользователю выбрать сценарий, а не собирать вручную низкоуровневые flags. Внутри все
сценарии должны разворачиваться в одну invariant-first модель:
`ScenarioProfile -> ResolvedCommand/LaunchPlan -> scenario-specific execution plan`.

## Архитектурные принципы

- Один общий execution kernel для всех сценариев.
- Scenario-first public API поверх invariant-first runtime.
- Малое число public concepts, широкие возможности через композицию policies.
- Каждый инвариант имеет владельца: value object, policy, resolver, state machine, validator или test.
- Сложные возможности появляются как modules/layers, а не как независимые подсистемы.
- Документация описывает только реализованное и проверенное поведение.

## Фаза 1: фундамент проекта

Цель: создать минимальную техническую основу без старого оверинжиниринга.

Состав:

- выбрать Java baseline;
- выбрать build layout;
- настроить formatting, unit tests, integration tests;
- создать public API examples как compile-tested snippets;
- создать deterministic process fixture или test utility;
- завести минимальный README, который не обещает будущий код;
- создать ADR для Java baseline и PTY packaging.

Выход фазы:

- проект собирается;
- examples компилируются;
- fixture может моделировать success, stderr, large output, timeout.

## Фаза 2: one-shot execution kernel

Цель: надежный запуск команды с bounded output и корректным lifecycle.

Состав:

- `CommandSpec`;
- `CommandService` или `CommandExecutor`;
- `CommandResult`;
- `RunOptions`;
- `CapturePolicy`;
- `ShutdownPolicy`;
- direct argv запуск;
- explicit shell mode;
- stdout/stderr drain;
- timeout supervision;
- soft-then-hard shutdown;
- structured failure model.

Ключевой инвариант:

- runtime получает только валидированный `ExecutionPlan`.

Выход фазы:

- сценарий `run` готов для реального использования;
- покрыты one-shot behavior checks;
- public API не раскрывает внутренние transport детали.

## Фаза 3: scenario profile и resolver layer

Цель: отделить пользовательский сценарий от runtime parameters.

Состав:

- `ScenarioProfile` как internal или narrow public concept;
- resolver для `CommandSpec + InvocationDraft + ScenarioProfile`;
- default profiles для `run`;
- per-call overrides;
- model для stdin input, output capture, terminal preference, timeout, shutdown;
- tests на конфликтующие overrides.

Ключевой инвариант:

- пользователь выбирает сценарий, resolver выбирает policies.

Выход фазы:

- API остается простым;
- новые сценарии можно добавлять без дублирования runtime.

## Фаза 4: interactive session

Цель: первый класс для долгоживущих процессов.

Состав:

- `Session`;
- `SessionOptions`;
- lifecycle state machine;
- raw stdin/stdout/stderr access;
- `send`, `sendLine`, `closeStdin`;
- `onExit`;
- close idempotency;
- idle timeout;
- cancellation path через общий shutdown policy.

Ключевой инвариант:

- session lifecycle имеет одного владельца и не размазан между streams, timeout и caller code.

Выход фазы:

- сценарий `interactive` готов;
- raw session не обещает line-level serialization.

## Фаза 5: line-oriented workflows

Цель: удобный сценарий для REPL и request/response CLI.

Состав:

- `LineSession`;
- `ResponseDecoder`;
- bounded transcript;
- serialization of request/response cycle;
- per-request timeout;
- clear EOF/timeout/failure distinction;
- default line decoder;
- custom decoders через узкий интерфейс.

Ключевой инвариант:

- один request не перемешивается с другим.

Выход фазы:

- сценарий `lineSession` готов;
- пользователь не работает с raw streams для простого REPL.

## Фаза 6: expect automation

Цель: scripted prompt automation без превращения API в большой DSL.

Состав:

- `Expect`;
- literal match;
- regex match;
- send/sendLine;
- bounded transcript;
- timeout and EOF exceptions;
- failure output, пригодный для тестов;
- optional filters для ANSI/control sequences.

Ключевой инвариант:

- order send/expect виден и проверяем.

Выход фазы:

- prompt automation покрыт без внешней expect-библиотеки;
- API остается маленьким.

## Фаза 7: PTY transport

Цель: поддержать процессы, которым нужен настоящий terminal.

Состав:

- `TerminalPolicy`;
- `PtyProvider` SPI;
- pipe/PTY transport abstraction;
- terminal required vs auto vs disabled;
- clear unsupported behavior;
- Unix PTY support;
- Windows ConPTY strategy;
- signal mapping;
- minimal terminal size handling.

Ключевой инвариант:

- `required` terminal никогда не делает silent fallback в pipes.

Выход фазы:

- PTY доступен как transport capability;
- scenario API не загрязняется platform-specific деталями.

## Фаза 8: streaming и listen-only monitoring

Цель: сценарии `tail`, `logs --follow`, long-running output consumers.

Состав:

- `listen` или `stream` scenario;
- stdout/stderr publishers или listeners;
- backpressure story;
- bounded diagnostic windows;
- completion/failure signals;
- no-input mode;
- optional stdin close on start.

Ключевой инвариант:

- streaming не обязан хранить весь output.

Выход фазы:

- пользователь может слушать процесс без ручного thread management.

## Фаза 9: observability and diagnostics

Цель: дать пользователю диагностику без превращения runtime в logging framework.

Состав:

- structured events;
- redaction-friendly command echo;
- output truncation metadata;
- lifecycle events;
- timeout/shutdown events;
- listener SPI;
- optional transcript sink;
- test helpers for diagnostics.

Ключевой инвариант:

- diagnostics не меняют поведение execution.

Выход фазы:

- ошибки объяснимы;
- diagnostics можно подключить к logging/metrics/tracing.

## Фаза 10: Kotlin ergonomics

Цель: сделать Java API естественным из Kotlin, затем добавить optional Kotlin module.

Состав:

- Kotlin-friendly nullability;
- extension functions;
- suspending wrappers;
- Flow adapters для streaming/listen;
- idiomatic tests and examples;
- no Kotlin dependency in Java core.

Ключевой инвариант:

- core остается Java library, Kotlin удобство живет отдельно.

Выход фазы:

- Kotlin users получают idiomatic API без утяжеления core artifact.

## Фаза 11: process pooling

Цель: вернуть идею прогретых workers только после стабилизации sessions.

Состав:

- `pooled` scenario поверх `CommandService`;
- pool sizing;
- worker warmup;
- request timeout;
- worker lifetime and reuse limits;
- reset hooks;
- health checks;
- graceful drain;
- metrics snapshot;
- stateful conversation story только после базового pool.

Ключевой инвариант:

- pool не владеет новым runtime, а использует те же session primitives и валидированные launch/execution plans.

Выход фазы:

- expensive CLI/REPL startup можно amortize;
- pooling не загрязняет базовый API.

## Фаза 12: advanced scenario presets

Цель: дать пользователю готовые профили для распространенных задач без новых подсистем.

Возможные presets:

- command automation;
- environment diagnostics;
- REPL line mode;
- prompt automation;
- log following;
- binary output capture;
- terminal-required session;
- pooled warm worker.

Ключевой инвариант:

- preset — это scenario/profile, а не новый независимый runner.

Выход фазы:

- API становится шире по возможностям, но не шире по архитектуре.

## Фаза 13: CLI-backed integrations

Цель: сделать iCLI удобной базой для интеграций, включая MCP-like adapters, без привязки core к MCP.

Состав:

- JSON/JSONL codecs;
- framed stdin/stdout helpers;
- cancellation mapping;
- structured error mapping;
- examples for command-backed tools;
- optional MCP adapter module;
- security notes for untrusted tool output.

Ключевой инвариант:

- external protocol adapters не должны становиться частью core runtime.
- integration helpers используют существующие сценарии, а не создают новый launcher.

Выход фазы:

- iCLI можно использовать как process harness для CLI-backed tools.
- optional `:icli-integrations` содержит JSON/JSONL, Content-Length framing, cancellation/error mapping и compile-tested
  command-backed tool examples. Реальный MCP SDK adapter остается отдельным будущим модулем поверх этого слоя.

## Фаза 14: performance and stress

Цель: проверить, что надежность сохраняется под нагрузкой.

Состав:

- stress tests for large output;
- timeout churn;
- rapid session open/close;
- PTY stability tests;
- pooling contention tests;
- memory usage checks;
- optional JMH benchmarks;
- regression suite for deadlocks.

Ключевой инвариант:

- performance work не нарушает safety invariants.

Выход фазы:

- есть bounded regression data о deadlock safety и retained-output memory behavior.
- `stressTest` содержит bounded regression suite и входит в `check`; machine-dependent benchmarks остаются отдельной
  будущей задачей.

## Фаза 15: release hardening

Цель: подготовить библиотеку к публичному использованию.

Состав:

- stable package names;
- Javadocs for public API;
- README only for shipped behavior;
- versioning policy;
- compatibility policy;
- license/dependency review;
- cross-platform CI;
- release checklist;
- migration notes from old iCLI concepts.

Выход фазы:

- release candidate можно оценивать как OSS library, а не как эксперимент.
- Apache-2.0 license, CI matrix, release policies, dependency review, release checklist, migration notes, Javadoc
  artifacts и public package boundary tests добавлены в текущий срез.

## Итоговая форма максимальной версии

В максимальной версии пользователь видит сценарии:

```text
CommandService
  run(...)
  lineSession(...)
  interactive(...)
  listen(...)
  pooled(...)

Session
  expect()
```

А внутри все сценарии проходят через общую модель:

```text
ScenarioProfile
  + CommandSpec
  + scenario-specific invocation draft
  + Policy overrides
  -> ResolvedCommand/LaunchPlan
  -> scenario-specific execution plan
  -> Runtime
  -> Transport
```

Так библиотека сохраняет чистый API, но получает широкую выразительность: сценарии дают пользователю язык намерений,
а invariant-first ядро удерживает безопасность, предсказуемость и расширяемость.
