# Архитектура изолированных инвариантов

## Позиция

Широкие возможности Procwright создаются композицией небольших объектов с одним владельцем ответственности, а не
числом public entry points. Пользователь выбирает сценарий; Draft допускает только настройки с определенной семантикой
для этого сценария; runtime получает нормализованный snapshot.

```text
CommandSpec
  -> scenario Draft.with* (immutable snapshot)
  -> execute/open (explicit resource boundary)
  -> internal scenario settings
  -> validated execution/session plan
  -> process/session runtime
  -> typed result or failure
```

Test доказывает инвариант, но не владеет им. Документация объясняет контракт, но не заменяет реализацию.

## Уровни владения

### Command model

`CommandSpec` владеет данными, общими для всех сценариев одной команды: executable, базовыми args, working directory,
environment policy/overrides и shell mode. Он immutable, копирует mutable input и валидирует локальные значения при
каждом `with*`.

Scenario-specific timeout, capture, readiness, terminal, diagnostics, protocol limits и pool policy в `CommandSpec` не
помещаются.

### Scenario Draft

`RunScenario.Draft`, `InteractiveScenario.Draft`, `LineSessionScenario.Draft`,
`ProtocolSessionScenario.Draft` и `StreamScenario.Draft` являются write-only persistent configuration API:

- каждый `with*` возвращает новый Draft;
- исходная ветка остается неизменной и пригодна для повторного/конкурентного terminal call;
- локальная валидация отдельных значений и defensive copying выполняются в `with*`; комбинация настроек и
  нормализованный plan валидируются в `execute()`/`open()` до запуска процесса;
- Draft не вводит callback-based mutable configuration DSL; переданные scenario callbacks могут сохраняться только в
  immutable snapshot, getters не раскрываются;
- terminal method — единственная точка создания процесса или result.

`ExpectScenario.Draft` применяет ту же модель к prompt automation, а `LineSessionScenario.PoolDraft` и
`ProtocolSessionScenario.PoolDraft` — к pool lifecycle. Worker settings фиксируются до перехода в `pooled()`.

### Internal settings

Package-private immutable settings разделены по ответственности:

- launch context;
- run input/capture/output;
- session lifecycle и terminal;
- readiness;
- line/protocol limits и decoding;
- diagnostics;
- pool capacity, warmup, hooks и retirement.

Settings не являются public compatibility surface. Они переносят точный snapshot от Draft к runtime и не содержат
открытых ресурсов.

### Plan validation

Локальная проверка одного значения выполняется его policy/value object или `with*`. Проверка комбинации выполняется
при terminal call владельцем соответствующего plan. Например, совместимость capture destination и output mode нельзя
доказать одним setter-ом.

Runtime получает только согласованный plan и не угадывает, какие defaults или overrides имел в виду пользователь.
Для one-shot run `OneShotIoPlan` до launch один раз преобразует этот plan в OS redirects, stdin action и точное
множество I/O tasks.

### Stateful runtime

После запуска владельцем инварианта становится конкретный runtime component:

- первый сигнал для прекращения one-shot process wait — `OneShotSupervision`; итоговый outcome после capture и cleanup
  принадлежит `OneShotExecution`;
- единый absolute deadline ожидания процесса и output capture — `OneShotDeadline`;
- декодирование завершенных one-shot captures и success/typed decode-failure `CommandResult` snapshot —
  `OneShotResultAssembler`;
- session construction transaction — `SessionConstruction`;
- выбор одного process terminal owner, process outcome, logical output-mode settlement и единый public exit
  raw/line/protocol handles — `SessionTerminal`;
- pipe process launch — `ProcessLauncher`; ordinary и provider-bounded liveness — `ProcessLiveness`;
- ожидание natural exit — `ProcessExitWaiter`, накопление наблюдавшихся живых descendants —
  `LiveDescendantSnapshot`; process-tree shutdown state machine — `ProcessTreeShutdown`, bounded tree state —
  `ShutdownTreeState`, signals и JDK fallback — `ProcessShutdownSignals`, failure/interruption policy —
  `ShutdownFailureLedger`; facade — `ProcessLifecycle`, exact-once session cleanup — `SessionProcessCleanup`;
- bounded callback admission — `BoundedTaskLimits`, `BoundedTaskLimiter` и `BoundedTaskPermit`; запуск adaptive или
  session-affine execution owner-а задает `BoundedTaskRunner.TaskStarter`, lifecycle одного accepted вызова —
  `BoundedTaskExecution`;
- стабильные one-shot process streams — `OwnedStreams`, exact-once logical close одного stream — `OwnedStream`;
  physical close выполняется best effort и не входит в `CommandResult` publication;
- транзакционное приобретение session streams — `ProcessIoAcquisition`, exact-once claim и outcome best-effort close
  одного stream — `ProcessStreamResource`, bundle-level close и rollback — `ProcessIoResources`; живые process streams
  не резервируют глобальную close capacity. Готовые close operations попадают в bounded active set и bounded backlog;
  saturation или невозможность запустить close фиксируется как cleanup failure без физического close и не задерживает
  исходный terminal outcome;
- stdin serialization и logical close, output ownership и session-level close callbacks — `SessionResources`; public
  exit не зависит от physical close, а `SessionTerminal` ждёт только фиксированные при construction process outcome
  и logical output-mode settlement. Первый принятый non-exit primary claim имеет приоритет, пока public outcome не
  выбран; natural process success остаётся fallback. Пользовательский matcher/decoder не является дополнительным gate и
  не переписывает выбранный exit;
- выбор output consumer-а внутри resource owner — `SessionOutputOwnership`;
- bounded immutable cleanup snapshot — `KnownDescendants`; bounded process/provider traversal — `ProcessTreeScanner`;
  fresh owner каждой provider operation —
  `ProcessProviderOperationOwner`, cancellation — `ProcessProviderOperationCancellation`, reporting settlement —
  `ProcessProviderOperationSettlement`;
- line/protocol request serialization — `SerializedRequestGate`; active request и terminal arbitration —
  `LineSessionState` и `ProtocolSessionState`;
- stream использует canonical outcome `SessionTerminal`; `DefaultStreamSession` владеет только stopping admission и
  преобразованием canonical outcome в `StreamExit`;
- protocol request write/read — `ProtocolRequestWriter`, `ProtocolResponseReader`, complete text fields —
  `ProtocolTextFieldDecoder`, global response limits — `ProtocolResponseBudget`;
- output backlog — bounded queue владельца сценария;
- единый monitor, составные pool transitions и связанные с partition поля worker — `WorkerPoolState`; partition —
  `PoolPartition`, immutable policy — `WorkerPoolPolicy`;
- startup winner — `WorkerStartup`, temporal startup — `WorkerStartupCoordinator`;
- exact-once retirement — `WorkerRetirement`, post-monitor retirement batch — `WorkerRetirementCoordinator`;
- обязательные post-monitor retirement и terminal publication выбирает одна транзакция `WorkerPoolState`;
- pool commit — созданный до регистрации `PoolWorker`, bounded capacity `PoolPartition` и lease, создаваемый только
  после успешного `STARTING -> LEASED`;
- pool replenishment — `PoolReplenisher`, request lifecycle — `PooledRequestRunner`;
- construction/closing/failure/drain decision внутри state owner — `PoolTermination`, terminal outcome и
  cancellation-isolated views — его publication token;
- bounded retirement/report domains — `PoolLifecycleDispatcher`, delayed replenishment — `PoolReplenishmentScheduler`,
  cancellable scheduled turn — `PoolScheduledAttempt`, late failures — `PoolFailurePublisher`;
- transcript retention — bounded transcript owner;
- diagnostics delivery — diagnostic emitter/dispatcher.

Lifecycle owner выбирает один обязательный public outcome. Process outcome и logical settlement выбранного output mode
ограничивают его публикацию; potentially blocking physical stream close выполняется отдельно и не может переписать
готовый результат. Проигравшие line/protocol failures и helper-owned close failures отправляются отдельными bounded
best-effort reports. Mandatory runtime не мутирует переданный пользователем `Throwable`, но точная форма
cause/suppressed graph для secondary failures не является public API. Поэтому чужой `Throwable` monitor не задерживает
request, helper или terminal publication. Ни одна из обязательных lifecycle-моделей не обходит cause/suppressed graph,
не использует глобальную блокировку между lifecycle owners и не создает unbounded fallback thread.

### Transport

Transport владеет OS-specific launch, pipe/PTY выбором, сигналами и process-tree возможностями. Public API выражает
только `TerminalPolicy`, `TerminalSize`, `TerminalSignal`, `PtyProvider` и `PtyRequest`; platform details не превращаются в
scenario flags.

## Основные инварианты

### API

- `Procwright.command(...)` — единственная фабрика сервиса вокруг команды.
- Пользователь всегда явно выбирает сценарий.
- `execute()`/`open()` не скрыты в configuration callback.
- Public scenario configuration carriers вне Draft отсутствуют.
- Public sealed session handles принадлежат Procwright и не являются SPI.

### Command и security

- executable обязателен, args сохраняют порядок;
- direct argv является default, shell mode включается явно;
- env keys и values валидируются до запуска;
- working directory представлен `Path`;
- failures и diagnostics не раскрывают raw argv/env values;
- CLI output считается недоверенными данными.

### I/O и память

- stdout/stderr дренируются без взаимной блокировки;
- capture, transcript, line/protocol backlog и decoder pending state имеют независимые bounds;
- truncation, malformed decoding и redaction отражаются явно;
- streaming применяет backpressure и не удерживает весь output;
- request/response byte, char и line limits не подменяются transcript limit;
- некорректный или non-progressing codec не может бесконечно удерживать caller или наращивать память.

### Lifecycle

- timeout, explicit close и failure используют общий shutdown policy;
- process-tree cleanup повторно обнаруживает поздних descendants в пределах phase deadline;
- `close()` идемпотентен; первый terminal outcome не заменяется более поздним typed failure или JVM `Error`;
- terminal failure helper-сценария выбирается в общем session lifecycle до shutdown и завершает helper `onExit()`
  exceptionally;
- `onExit()` завершается ровно один раз;
- readiness выполняется после launch, но до возврата handle или перевода worker в idle;
- partial construction failure закрывает все уже созданные ресурсы.

### Concurrency и ownership

- terminal calls одного Draft создают независимые процессы;
- raw `Session` не обещает request serialization;
- `LineSession` и `ProtocolSession` допускают только один request/response cycle одновременно;
- output mode выбирается до launch; raw streams и runtime pump нельзя получить из одного scenario handle;
- runtime pump получает exclusive ownership stdout/stderr выбранного helper scenario;
- readiness probes и worker hooks выполняются через независимые bounded admission domains; это внутренняя защита от
  неограниченной служебной работы, а не квота на процессы или pools. Зависший callback удерживает разрешение только
  своей категории до фактического возврата;
- readiness, worker hooks, protocol callbacks, custom charset encoding, blocking stdin writes и regex evaluation
  используют task-scoped adaptive owner: Java 24+ дает каждому invocation non-inheriting virtual thread, Java 17–23 —
  fresh non-inheriting daemon platform thread; callback thread не переходит другому invocation, а раннее monitor pinning
  virtual threads не уменьшает фактическую bounded capacity;
- stream listener вызывается синхронно на output pump; вызовы stdout/stderr сериализуются локально для одной session,
  создают естественный backpressure и не используют отдельный поток или process-wide квоту;
- process provider boundary принимает не более 32 operations одновременно; каждый accepted invocation выполняется на
  fresh disposable non-inheriting daemon owner-е, а permit удерживается до фактического возврата operation, включая
  abandoned call после timeout или interruption;
- provider owners не переиспользуются, поэтому arbitrary `ThreadLocal` и mutable thread state не переносятся между
  operations;
- admission ограничивает выполняющиеся и abandoned operations; callback queues не растут без границы;
- nullable комбинации execution owners не входят в bounded-task state machine: каждый accepted вызов заранее получает
  ровно одного task-scoped adaptive или session-affine owner-а, явную cancellation policy и явный tracked/untracked
  handoff;
- после abandonment поздний результат или failure callback не меняет уже выбранный timeout/cancellation outcome и не
  публикуется отдельно; callback по-прежнему удерживает admission до фактического возврата;
- request callback не удерживает public process `onExit()` после settlement output transport; его поздний failure
  остаётся исходом синхронного request и не переписывает готовый process result;
- асинхронный отказ injected `TaskStarter` до abandonment возвращается как execution failure; после abandonment это
  поздний execution outcome, который только завершает permit settlement и не заменяет выбранный outcome;
- diagnostics сохраняют порядок для одного destination и отдают dispatcher после bounded batch; между разными
  destination порядок и fairness не являются контрактом;
- interrupt синхронного caller-а восстанавливает interrupt status и не обходит cleanup;
- поздняя physical-close failure может быть отправлена как best-effort report после своего settlement, но не задерживает
  и не изменяет public terminal outcome;
- terminal futures не резервируют отдельные publication threads; синхронные continuations следуют стандартному
  контракту `CompletableFuture`;
- coroutine cancellation закрывает/retire только session или worker с недостоверным protocol state; ожидание общего
  exit future не получает ownership над процессом.

### Protocol

- `ProtocolAdapter` владеет request framing и определением конца response;
- scenario Draft хранит factory, а `ScenarioRuntime` вызывает ее отдельно перед запуском каждого session/worker;
- concurrent terminal calls могут вызывать factory конкурентно, поэтому factory обязана быть thread-safe;
- adapter создается до process launch; `null` и factory failure не оставляют процесс;
- deadline охватывает validation/encoding, serialized access, write и decode;
- protocol failure закрывает session, потому что дальнейшее framing state неизвестно;
- stable reason enum отделяет timeout, EOF, broken pipe, decode, oversize, backlog и adapter failure.

### Pool

- pool использует существующий line/protocol runtime и не раскрывает lease;
- line и protocol pool handles остаются сценарными, но общий pool lifecycle выражают одни
  `PooledSessionMetrics` и `PooledSessionException`; request failures остаются line/protocol-specific;
- каждый worker всегда принадлежит ровно одному состоянию: starting, idle, leased или retiring;
- `maxSize` одного pool принимает значения от 1 до 256, по умолчанию равен 1 и ограничивает starting, idle, leased и
  retiring slots этого pool;
- разные pools и direct sessions не делят process-global worker quota; суммарное число процессов контролирует приложение
  количеством создаваемых ресурсов и `maxSize` каждого pool;
- startup и hooks имеют bounded admission; retirement processing использует fixed owner set и bounded queue с
  caller-runs backpressure при насыщении. Эти механизмы не являются пользовательской resource policy;
- pool terminal outcome выбирается под monitor и публикуется после его освобождения без отдельной lifetime reservation;
- acquire timeout и request timeout различаются;
- failed request/timeout/decoder/process exit retire worker;
- reset/health hooks bounded и не выполняются одновременно с пользовательским request;
- close запрещает новые requests, закрывает idle workers и дает активным requests завершить установленный lifecycle;
- metrics являются снимком наблюдаемого состояния, а retirement reason не выводится из текста exception.

### Integrations и Kotlin

- optional modules не создают второй process runtime;
- Kotlin extensions работают с теми же Java Draft и handles;
- `openFlow()` cold: каждая collection открывает и закрывает собственную `StreamSession`;
- `protocolAdapterFactory { ... }` создает отдельный adapter wrapper на каждый factory call;
- JSON/framing helpers валидируют границы до domain parsing и не публикуют unbounded raw output в errors.

## Анти-паттерны

Недопустимы:

- boolean soup вместо policy/value object;
- второй публичный dialect через дополнительные configuration carriers;
- instance adapter, разделяемый несколькими protocol sessions;
- validation, размазанная по launcher и runtime;
- getter-rich Draft, превращающийся в domain model;
- hidden process spawn в `with*`, preset или Kotlin configuration block;
- unbounded queue/executor как fallback cleanup;
- новый scenario name без отдельного lifecycle или invariant set;
- тест, который закрепляет внутреннюю структуру вместо public behavior.

## Проверка

Для каждого инварианта в [quality/invariant-proof-map.md](quality/invariant-proof-map.md) должны быть указаны владелец и
proof. До первого выпуска public surface проверяется целевыми surface tests и compilation внешних consumers. Stateful
инварианты проверяются unit, integration и bounded stress tests; platform capability — отдельной CI matrix.

Архитектурное изменение принимается только после ответа на три вопроса:

1. Какой пользовательский сценарий стало возможно выразить?
2. Какой компонент единолично владеет новым правилом?
3. Какая проверка отличает правильное поведение от похожего, но ошибочного?
