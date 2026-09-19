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
Для one-shot run `OneShotIoPlan` до launch один раз преобразует этот plan в OS redirects, stdin action и признак
необходимости I/O task executor.

### Stateful runtime

После запуска владельцем инварианта становится конкретный runtime component:

- первый сигнал для прекращения one-shot process wait — `OneShotSupervision`; итоговый outcome после capture и cleanup
  принадлежит `OneShotExecution`; успешный shutdown после interruption не запускается повторно;
- единый absolute deadline ожидания процесса и output capture — `OneShotDeadline`;
- stdin/output tasks принадлежат одному execution и не используют глобальную квоту; фактическая I/O failure после
  отмены `Future` остаётся доступна supervision через `OneShotTask`;
- декодирование завершенных one-shot captures и success/typed decode-failure `CommandResult` snapshot —
  `OneShotResultAssembler`;
- session construction transaction — `SessionConstruction`;
- выбор одного process terminal owner, process outcome, logical output-mode settlement и единый public exit
  raw/line/protocol handles — `SessionTerminal`;
- pipe process launch — `ProcessLauncher`; наблюдение trusted process liveness — `ProcessLiveness`;
- ожидание natural exit — `ProcessExitWaiter`, накопление наблюдавшихся живых descendants —
  `LiveDescendantSnapshot`; process-tree shutdown state machine — `ProcessTreeShutdown`, bounded tree state —
  `ShutdownTreeState`, signals и JDK fallback — `ProcessShutdownSignals`, failure/interruption policy —
  `ShutdownFailureLedger`; facade — `ProcessLifecycle`, exact-once session cleanup — `SessionProcessCleanup`;
- wall-clock containment потенциально блокирующего callback — `TimedTaskRunner`; сериализация и запрет повторного
  callback после abandonment принадлежат конкретному scenario owner (`SerializedRequestGate`, session state или
  worker lifecycle), поэтому независимые handles не делят глобальную admission capacity. Cancellation signal содержит
  одну активную регистрацию; её identity защищает следующий callback от позднего unregister предыдущего;
- стабильные one-shot process streams — `OwnedStreams`, exact-once logical close одного stream — `OwnedStream`;
  physical close выполняется best effort и не входит в `CommandResult` publication;
- транзакционное приобретение session streams — `ProcessIoAcquisition`, exact-once claim и outcome best-effort close
  одного stream — `ProcessStreamResource`, bundle-level close и rollback — `ProcessIoResources`; живые process streams
  не резервируют глобальную close capacity. Готовые close operations независимо попадают в bounded active set и bounded backlog;
  saturation или невозможность запустить close фиксируется как cleanup failure без физического close и не задерживает
  исходный terminal outcome;
- stdin serialization и logical close, output ownership и session-level close callbacks — `SessionResources`; public
  exit не зависит от physical close, а `SessionTerminal` ждёт только фиксированные при construction process outcome
  и logical output-mode settlement. Первый принятый non-exit primary claim имеет приоритет, пока public outcome не
  выбран; natural process success остаётся fallback. Пользовательский matcher/decoder не является дополнительным gate и
  не переписывает выбранный exit;
- выбор output consumer-а внутри resource owner — `SessionOutputOwnership`;
- logical output-mode settlement и единственная передача stdout/stderr на physical close после process outcome —
  `OutputPumpCleanup`; закрытие wrapper-а отдельной pump не выбирает момент physical close;
- bounded immutable cleanup snapshot — `KnownDescendants`; bounded descendant traversal — `ProcessTreeScanner`;
  scan admission, deadline и interrupt уже запущенного disposable worker — `ProcessScanOperationOwner`. Slot занят до
  физического возврата операции; поздний result не участвует в lifecycle outcome и не требует reporting settlement;
- line/protocol request serialization — `SerializedRequestGate`; active request и terminal arbitration —
  `LineSessionState` и `ProtocolSessionState`;
- stream использует canonical outcome `SessionTerminal`; `DefaultStreamSession` владеет только stopping admission и
  преобразованием canonical outcome в `StreamExit`;
- protocol request write/read — `ProtocolRequestWriter`, `ProtocolResponseReader`, complete text fields —
  `ProtocolTextFieldDecoder`, global response limits — `ProtocolResponseBudget`;
- bounded storage неопубликованного decode output — `BoundedCharacterStaging`; runtime decoders владеют operation
  boundaries, но не дублируют алгоритм роста и освобождения staging buffer;
- output backlog — bounded queue владельца сценария;
- единый monitor, составные pool transitions и связанные с partition поля worker — `WorkerPoolState`; partition —
  `PoolPartition`, immutable policy — `WorkerPoolPolicy`;
- startup winner и его typed outcome — `WorkerStartup`, запуск и отображение результата без повторной arbitration —
  `WorkerStartupCoordinator`;
- exact-once retirement — `WorkerRetirement`, post-monitor retirement batch — `WorkerRetirementCoordinator`;
- обязательные post-monitor retirement и terminal publication выбирает одна транзакция `WorkerPoolState`;
- pool commit — созданный до регистрации `PoolWorker`, bounded capacity `PoolPartition` и lease, создаваемый только
  после успешного `STARTING -> LEASED`;
- pool replenishment, единственная pending/running attempt и её cancellation handle под одним monitor —
  `PoolReplenisher`; request lifecycle — `PooledRequestRunner`;
- construction/closing/failure/drain decision внутри state owner — `PoolTermination`, terminal outcome и
  cancellation-isolated views — его publication token;
- bounded retirement dispatch — `PoolLifecycleDispatcher`, delayed replenishment — `PoolReplenishmentScheduler`,
  late failures — `PoolFailurePublisher` с прямой best-effort отправкой в `BoundedFailureReporter`;
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
- capture и transcript имеют собственные bounds; line/protocol backlog и decoder pending state ограничены
  соответствующими response limits без отдельных transport settings;
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
- readiness, worker hooks, protocol callbacks, blocking stdin writes и regex evaluation выполняются на fresh
  non-inheriting daemon thread; deadline ограничивает ожидание caller. Cancellable session callback выбирает terminal
  state до interrupt через abandonment handler; остальные owners обрабатывают timeout/interruption до допуска следующей
  операции. Глобальной admission capacity между независимыми handles нет;
- line request encoding выполняется синхронно с проверками deadline и interruption между шагами. Procwright не
  изолирует реализацию `Charset`, которая сама не возвращает управление из JDK method;
- stream listener вызывается синхронно на output pump; вызовы stdout/stderr сериализуются локально для одной session,
  создают естественный backpressure и не используют отдельный поток или process-wide квоту;
- descendant scanner принимает не более 32 scan operations одновременно; каждый accepted invocation выполняется на
  fresh disposable non-inheriting daemon owner-е, а permit удерживается до фактического возврата operation, включая
  abandoned call после timeout или interruption;
- scan owners не переиспользуются, поэтому arbitrary `ThreadLocal` и mutable thread state не переносятся между scans;
- custom `PtyProvider` и возвращённые `Process`/`ProcessHandle` являются trusted SPI без индивидуальной per-call isolation;
  metadata/signals должны возвращаться promptly, timed waits — соблюдать timeout. Их блокировка может превысить
  deadline session/cleanup. Bounded scan и asynchronous destroy fallback сохраняются; system provider самостоятельно
  ограничивает capability detection и bootstrap;
- один callback task не имеет внутренней очереди: новый fresh thread либо начинает callback до deadline, либо
  cancellation/timeout переводит его из `PENDING` в `ABANDONED` до входа в пользовательский код;
- после abandonment поздний результат или failure уже начатого callback не меняет выбранный timeout/cancellation
  outcome и не публикуется отдельно;
- request callback не удерживает public process `onExit()` после settlement output transport; его поздний failure
  остаётся исходом синхронного request и не переписывает готовый process result;
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
- `readTextExactly` читает bounded chunks, проверяет charset/character limits до следующего read и при terminal failure
  не обещает exact consumed byte count; успешное чтение сохраняет byte-length framing;
- stable reason enum отделяет timeout, EOF, broken pipe, decode, oversize и adapter failure; pending output и
  потреблённый response используют общий `RESPONSE_TOO_LARGE` при превышении соответствующего response limit.

### Pool

- pool использует существующий line/protocol runtime и не раскрывает lease;
- line и protocol pool handles остаются сценарными, но общий pool lifecycle выражают одни
  `PooledSessionMetrics` и `PooledSessionException`; request failures остаются line/protocol-specific;
- каждый worker всегда принадлежит ровно одному состоянию: starting, idle, leased или retiring;
- `maxSize` одного pool принимает значения от 1 до 256, по умолчанию равен 1 и ограничивает starting, idle, leased и
  retiring slots этого pool;
- разные pools и direct sessions не делят process-global worker quota; суммарное число процессов контролирует приложение
  количеством создаваемых ресурсов и `maxSize` каждого pool;
- `maxSize` ограничивает одновременно starting workers одного pool; независимые pools не делят startup admission.
  Ожидание hooks ограничено deadline; некооперативный callback может завершиться позднее, но worker после timeout не
  переиспользуется. Retirement processing использует fixed owner set и bounded queue с caller-runs backpressure;
- pool terminal outcome выбирается под monitor и публикуется после его освобождения без отдельной lifetime reservation;
- acquire timeout и request timeout различаются;
- failed worker request/timeout/decoder/process exit retire worker; локальная подготовка line request до обращения к
  session возвращает незатронутый worker без reset и без увеличения его request count;
- line preflight выполняется до acquire, encoded byte array создаётся после; обе фазы расходуют request budget,
  acquire wait из него исключён;
- заданные reset/health hooks bounded и не выполняются одновременно с пользовательским request; отсутствующие hooks
  не создают timed task;
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
