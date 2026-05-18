# Глубокий аудит компонентов и классов 2026-05-18

## Назначение

Этот аудит дополняет глобальный архитектурный аудит. Фокус ниже уровня модулей и пакетов: отдельные компоненты,
классы, владельцы инвариантов, фактическое тестовое покрытие, соотношение public API с runtime-инвариантами, SOLID и
GRASP.

Аудит не вносит исправления. Он фиксирует, где текущая реализация уже достаточно зрелая, а где качество держится на
сценарных тестах или договоренности, но owner инварианта в коде еще недостаточно минимален.

Статус исправлений зафиксирован в
[audit-finding-remediation-2026-05-18.md](audit-finding-remediation-2026-05-18.md).

## Методика

Проверены production classes core, `:icli-integrations` и `:icli-kotlin`, а также unit, integration и stress tests.
Количественного line/branch coverage gate в проекте сейчас нет, поэтому оценка покрытия качественная: проверялись
инварианты, критические состояния, failure paths и архитектурные boundary tests.

Критерии:

- инвариант имеет одного явного владельца в коде;
- test/eval доказывает инвариант, но не заменяет владельца;
- public API не заставляет пользователя мыслить низкоуровневыми process flags;
- runtime classes имеют высокую связность и не смешивают независимые state machines;
- public value/results не позволяют легко создать невозможное доменное состояние;
- документация описывает только поведение, которое закреплено тестами.

## Итог

Общая картина сильная для pre-release rewrite. В проекте есть 47 test/fixture files, отдельные unit, integration и
stress tiers, boundary tests для public API, пакетов, JPMS и terminal capability. Сценарии `run`, `interactive`,
`lineSession`, `listen`, `pooled`, diagnostics, PTY boundary и integrations покрыты лучше, чем обычно на такой стадии.

Главный риск не в отсутствии тестов как таковых, а в том, что несколько важных инвариантов пока не минимизированы:

- часть redaction-инварианта `Expect` живет в transcript formatting, но не в exception message policy;
- pooled default resolution частично живет во facade `CommandService`;
- bounded transcript logic повторяется в трех runtime helpers;
- `DefaultSession` удерживает слишком много независимых lifecycle/output/stdin/timeout инвариантов;
- некоторые public result/metrics records валидируют только скалярные значения, но не доменные связи между ними.

## Coverage matrix

| Компонент | Основной owner инвариантов | Текущие тесты | Оценка |
| --- | --- | --- | --- |
| `CommandSpec`, invocation builders, options | value objects/builders | `CommandInvocationTest`, `SessionInvocationTest`, `LineSessionInvocationTest`, `StreamInvocationTest`, `PooledLineSessionInvocationTest`, `PolicyValueTest` | Хорошо. Валидация и immutable snapshots покрыты. |
| `ExecutionPlanResolver` | default/override resolution, launch shape, terminal rejection | `ExecutionPlanResolverTest`, `TerminalCapabilityBoundaryTest` | Хорошо. Это один из лучших invariant owners. |
| `ProcessKernel`, `ProcessLifecycle` | one-shot capture, timeout, stdin, tree cleanup | `OneShotExecutionIntegrationTest`, `ProcessStressTest`, diagnostics tests | Хорошо. Прямых unit-тестов мало, но process behavior лучше проверять интеграционно. |
| `CommandService` | scenario facade/controller | `CommandServiceTest`, scenario integration tests | Достаточно, но pooled defaults размазывают owner default resolution. |
| `DefaultSession` | raw session lifecycle, stdin guard, idle timeout, output ownership | `InteractiveSessionIntegrationTest`, `SessionOutputOwnershipTest`, `ProcessStressTest` | Behavior покрыт хорошо. Класс перегружен как owner нескольких state machines. |
| `DefaultLineSession` | serialized request/response, stdout backlog, bounded transcript | `LineSessionIntegrationTest`, pooled tests | Хорошо по behavior. Transcript invariant продублирован локально. |
| `DefaultExpect` | expect matching, match buffer, transcript redaction | `ExpectIntegrationTest`, output ownership tests | Функционально хорошо, но есть P2 gap по redaction exception messages. |
| `DefaultStreamSession` | stream pumps, listener delivery, close/timeout/onExit | `StreamScenarioIntegrationTest`, diagnostics tests | Хорошо. Listener serialization и future copy покрыты явно. |
| `DefaultPooledLineSession` | worker lease/retire/drain lifecycle | `PooledLineSessionIntegrationTest`, `ProcessStressTest` | Хорошо. Metrics record слабее runtime state machine. |
| Diagnostics | safe command echo, async best-effort delivery, schema | `DiagnosticsOptionsTest`, `DiagnosticsIntegrationTest` | Хорошо по безопасности/schema. Ordering semantics остаются best-effort. |
| Terminal/PTY boundary | terminal capability только в session-family | `TerminalCapabilityBoundaryTest`, PTY integration/stress when available | Хорошо. Platform-dependent PTY behavior проверяется условно. |
| Package/API boundary | public/internal split | `PublicApiSurfaceTest`, `PackageBoundaryTest`, `module-info.java` | Очень хорошо. Boundary закреплен механически. |
| `:icli-integrations` JSON/framing/tool adapters | JSON model, framing, CLI tool adapter | `JsonCodecTest`, `ContentLengthJsonFramesTest`, `CommandBackedToolTest`, `CliAdapterErrorTest` | Хорошо для MVP. Есть edge-case gaps для protocol hardening. |
| `:icli-kotlin` | thin Kotlin ergonomics | Kotlin tests, KDoc coverage checks | Достаточно для optional layer. |

## Findings

### P2: `Expect` redaction покрыт в transcript, но не покрыт в exception messages

- `file`: `src/main/java/com/github/ulviar/icli/internal/session/DefaultExpect.java`
- `lines`: 100, 110, 134, 145, 291
- `tests`: `src/integrationTest/java/com/github/ulviar/icli/ExpectIntegrationTest.java`, lines 131-140, 164-178
- `problem`: `transcriptValue(...)` редактирует action values в transcript по умолчанию, но timeout/EOF messages собираются
  из raw expected text или raw regex pattern.
- `coverage gap`: тесты проверяют, что transcript не содержит raw value, но не проверяют `exception.getMessage()`.
- `impact`: redaction invariant имеет два канала вывода, и один канал сейчас не управляется тем же policy owner.
- `SOLID/GRASP`: нарушен Information Expert для redaction policy: `transcriptValue(...)` знает policy, а message creation
  обходит это знание.
- `recommended fix`: через TDD добавить regression tests для text/regex timeout и EOF, затем ввести единый
  `expectedDescription(...)`/message policy внутри `DefaultExpect` или отдельный tiny value/helper. При default options
  message не должен содержать raw expected value; при `VERBATIM` может содержать printable value.

### P2: pooled default resolution живет во facade `CommandService`

- `file`: `src/main/java/com/github/ulviar/icli/CommandService.java`
- `lines`: 346-354, 402-410
- `related`: `src/main/java/com/github/ulviar/icli/session/PooledLineSessionInvocation.java`, lines 66-74, 275-277
- `problem`: `CommandService.pooledInvocationBuilder()` вручную переносит каждое поле `PooledLineSessionOptions` в builder.
- `coverage gap`: есть тесты builder defaults и pooled behavior, но нет отдельного invariant test, что service-level pooled
  defaults всегда применяются при добавлении новых fields.
- `impact`: при расширении `PooledLineSessionOptions` легко забыть синхронизировать facade. Это риск для scenario-first API:
  пользователь задает сценарий и defaults, но default resolution не имеет единого owner.
- `SOLID/GRASP`: `CommandService` как Controller начинает знать детали default resolution, которые лучше принадлежат
  resolver/domain boundary.
- `recommended fix`: вынести pooled default application в internal owner, например `PooledLineSessionInvocationResolver`
  или package-private factory рядом с session runtime. Public builder можно оставить без overload с defaults, но перенос
  policy fields должен быть централизован и покрыт тестом.

### P3: `DefaultSession` слишком крупный owner нескольких независимых инвариантов

- `file`: `src/main/java/com/github/ulviar/icli/internal/session/DefaultSession.java`
- `lines`: 42-60, 237-350, 374-430, 457+
- `tests`: `InteractiveSessionIntegrationTest`, `SessionOutputOwnershipTest`, `ProcessStressTest`
- `problem`: класс одновременно владеет raw stdin wrapper, guarded output streams, output ownership, lifecycle state,
  idle watcher, exit watcher, shutdown diagnostics и resource cleanup.
- `coverage`: поведение покрыто хорошо, включая idempotent close, idle timeout, output ownership и future copy.
- `impact`: тесты страхуют внешнее поведение, но локальные инварианты трудно удерживать при изменениях. Любая доработка
  raw session рискует задеть несколько state transitions.
- `SOLID/GRASP`: SRP и High Cohesion под давлением; Low Coupling сохранен на уровне пакетов, но внутри класса слишком
  много причин для изменения.
- `recommended fix`: не дробить механически. После закрытия P2 выделить только реальные sub-owners: output ownership
  guard, stdin state guard и idle/exit watcher coordination. Сначала закрепить текущие state transitions targeted tests.

### P3: bounded transcript invariant продублирован в трех runtime helpers

- `files`:
  - `src/main/java/com/github/ulviar/icli/internal/session/DefaultLineSession.java`, lines 381-421
  - `src/main/java/com/github/ulviar/icli/internal/session/DefaultExpect.java`, lines 298-353
  - `src/main/java/com/github/ulviar/icli/internal/session/DefaultStreamSession.java`, lines 281-328
- `tests`: `LineSessionIntegrationTest`, `ExpectIntegrationTest`, `StreamScenarioIntegrationTest`, diagnostics tests
- `problem`: bounded retention, stream labels, truncation flags and action/stream formatting are local copies with
  похожей, но не общей логикой.
- `coverage`: каждый сценарий отдельно покрывает bounded transcript behavior.
- `impact`: invariant architecture требует одного owner для повторяющегося правила. Сейчас расхождение semantics возможно
  без падения соседних scenario tests.
- `SOLID/GRASP`: duplication снижает cohesion и ухудшает Protected Variations: изменение transcript semantics придется
  повторять в нескольких местах.
- `recommended fix`: выделять общий owner только если он сохранит различия сценариев. Возможная форма:
  `BoundedTranscriptBuffer` с маленькими strategy hooks для action visibility/redaction и stream labels. Не переносить
  matching buffer `Expect` в тот же abstraction.

### P3: public result/metrics records не полностью выражают доменные связи

- `files`:
  - `src/main/java/com/github/ulviar/icli/session/PooledLineSessionMetrics.java`, lines 14-28
  - `src/main/java/com/github/ulviar/icli/command/CommandResult.java`, lines 22-66
- `problem`: records проверяют null/non-negative values, но почти не проверяют relational invariants. Например,
  `PooledLineSessionMetrics` допускает `idle + leased > size`; `CommandResult` допускает несогласованные bytes и decoded
  strings.
- `coverage`: runtime tests проверяют корректные snapshots из реального pool/process, но public constructors позволяют
  пользователю создать невозможные состояния.
- `impact`: это не runtime bug, но API классы слабее заявленной идеи "инварианты изолированы в коде".
- `SOLID/GRASP`: Information Expert есть у record constructor, но он знает только скалярные правила, не доменную модель.
- `recommended fix`: для metrics добавить relational checks. Для `CommandResult` аккуратно решить API-историю: либо
  оставить как snapshot DTO и задокументировать looseness, либо сделать canonical factories/runtime-only stronger path.
  Не ломать public convenience constructor без отдельного compatibility решения.

### P3: diagnostics ordering semantics не закреплены как контракт

- `file`: `src/main/java/com/github/ulviar/icli/internal/DiagnosticEmitter.java`
- `tests`: `DiagnosticsOptionsTest`, `DiagnosticsIntegrationTest`
- `problem`: delivery intentionally best-effort async; тесты проверяют containment, schema, non-blocking и safety, но не
  ordering. Это соответствует текущему коду, но не явно закреплено как user-visible contract.
- `impact`: если diagnostics расширится до richer event stream, пользователи могут ожидать ordered lifecycle transcript.
- `recommended fix`: либо явно задокументировать unordered best-effort delivery, либо добавить order-preserving bounded
  dispatcher как отдельный owner до расширения diagnostics.

### P3: integration protocol tests можно усилить edge-case coverage

- `files`:
  - `icli-integrations/src/main/java/com/github/ulviar/icli/integration/ContentLengthJsonFrames.java`, lines 128-147
  - `icli-integrations/src/test/java/com/github/ulviar/icli/integration/ContentLengthJsonFramesTest.java`, lines 29-92
  - `icli-integrations/src/test/java/com/github/ulviar/icli/integration/JsonCodecTest.java`, lines 28-62
- `problem`: framing and JSON parser already cover основные failure paths, но нет тестов для header byte limit,
  incomplete escape/unicode sequences и некоторых numeric edge cases.
- `impact`: optional integrations module уже пригоден для MVP, но protocol-facing code лучше довести до hardened edge
  matrix до release.
- `recommended fix`: добавить компактную matrix of invalid JSON/framing cases. Не подключать внешнюю JSON library только
  ради тестов; лучше добавить targeted tests вокруг уже выбранного минимального parser.

## SOLID и GRASP

### SOLID

- **Single Responsibility.** Хорошо у value objects, options, resolvers и protocol helpers. Слабые места:
  `DefaultSession`, `CommandService` pooled defaults, повторяющиеся transcript buffers.
- **Open/Closed.** Scenario set намеренно закрыт и typed. Это не нарушение: библиотека фокусируется на фиксированных
  сценариях, а расширение идет через presets и optional modules.
- **Liskov Substitution.** Есть осознанный риск non-SPI public interfaces (`Session`, `Expect`, etc.): пользователь может
  подумать, что custom implementation supported. Это уже описано в предыдущем аудите и Javadoc, но форма API остается
  компромиссом.
- **Interface Segregation.** В целом соблюден. `Session` широковат, но он intentionally raw handle; остальные scenario
  interfaces узкие.
- **Dependency Inversion.** Хорошо: core опирается на JDK abstractions, PTY спрятан за `PtyProvider`, optional
  integrations не создают второй process runtime.

### GRASP

- **Controller.** `CommandService` правильно выступает public scenario controller. Риск: controller начинает владеть
  pooled default mapping.
- **Creator.** Builders создают invocation drafts; runtime создает resolved plans и sessions. В целом хорошо.
- **Information Expert.** Сильные примеры: `ExecutionPlanResolver`, `ProcessLifecycle`, `PooledLineSessionOptions`.
  Слабые: `DefaultExpect` redaction messages, public metrics/result relational invariants.
- **Low Coupling.** Package/API boundary tests дают сильную страховку. Optional modules не протекают в core.
- **High Cohesion.** Хорошо на уровне packages и value objects, слабее внутри `DefaultSession` и transcript logic.
- **Protected Variations.** PTY boundary, diagnostics schema и public API tests работают хорошо. Transcript/redaction и
  pooled default resolution пока защищены хуже.

## Достаточно ли покрыты тестами

Для текущего pre-release состояния покрытие по поведению достаточное в core-сценариях и лучше всего в областях:

- process lifecycle, timeout, shutdown, descendant cleanup;
- output capture, truncation, stdin;
- line-session serialization/backlog/failure paths;
- stream listener/timeout/close/onExit;
- pooled worker lifecycle/contention/drain;
- diagnostics schema/redaction;
- public API/package/terminal boundaries.

Недостаточно или неполно покрыты именно invariant gaps:

- `ExpectException.getMessage()` redaction для expected text/regex;
- service-level pooled defaults как единый invariant owner;
- relational invariants public result/metrics objects;
- shared transcript semantics across scenarios;
- protocol hardening edge matrix for integrations;
- explicit diagnostics ordering/non-ordering contract.

Линейный coverage plugin сейчас не нужен как главный следующий шаг. Более полезно добавить небольшую `context`/test
матрицу "invariant -> owner -> proof", а затем точечные regression tests по перечисленным gaps. Line coverage можно
добавлять позже, если release gate начнет терять видимость по непокрытым branches.

## Рекомендуемый порядок работ

1. Закрыть P2 по `Expect` redaction через TDD и обновить docs/security expectations.
2. Закрыть P2 по pooled default resolution, вынеся owner из `CommandService` и добавив targeted tests.
3. Добавить invariant coverage matrix: runtime invariant, owner class, proving tests, docs page.
4. Добавить relational checks в `PooledLineSessionMetrics`; отдельно решить, нужен ли stronger `CommandResult` factory.
5. Усилить integration protocol edge tests.
6. После этих шагов вернуться к `DefaultSession` и transcript buffers: рефакторить только под защитой новых tests и без
   потери scenario-first API.

## Короткий вывод

Проект не выглядит архитектурно "хрупким", но он еще не достиг заявленного максимума по изоляции инвариантов. Наиболее
важные runtime behaviors уже проверяются тестами. Следующий уровень зрелости — не больше абстракций, а более точное
распределение владельцев: redaction policy, pooled defaults, transcript retention, public snapshot invariants и raw
session state coordination.
