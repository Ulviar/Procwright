# ADR-0021: Декомпозиция scenario runtime и protocol session

## Статус

Принято.

## Контекст

Сценарный API должен оставаться компактным: пользователь выбирает `run`, `interactive`, `lineSession`,
`protocolSession`, `listen` или pool-вариант, а не собирает runtime из низкоуровневых частей. При этом внутренний код не
должен концентрировать разные инварианты в одном большом классе, если границу ответственности можно описать ясно.

## Решение

`CommandService` остается публичным facade-объектом вокруг команды и сценарных entry points. Persistent Draft хранит
scenario-specific internal settings. `ScenarioRuntime` строит execution/session plans из их snapshot, открывает runtime
wrappers, применяет readiness и передает diagnostics дальше в runtime.

`ProcessKernel` оркестрирует one-shot run, но не вычисляет OS redirects и конкурентный terminal outcome. Immutable
`OneShotIoPlan` переводит согласованный `ExecutionPlan` в redirects, stdin action и точное число I/O tasks до launch.
`OneShotTermination` выбирает первый из process exit, timeout и stdin failure; cleanup, diagnostics и перевод failure в
public lifecycle exception остаются у kernel. После cleanup `OneShotResultAssembler` декодирует завершенные captures и
строит success либо typed decode-failure `CommandResult`, сохраняя raw bytes в обоих случаях.

Общий process runtime также разделен по наблюдаемым инвариантам. `ProcessLauncher` владеет launch обычного pipe process.
`ProcessLiveness` консервативно определяет, доказан ли выход обычного процесса, а для guarded operations различает
`LIVE`, `EXITED`, исчерпанный lifecycle budget `UNKNOWN` и недоступное OS/provider state `UNOBSERVABLE`. Два последних
состояния не доказывают выход. `ProcessExitWaiter` владеет caller-thread polling и wait deadline, а
`LiveDescendantSnapshot` накапливает bounded immutable snapshot живых либо временно недоступных для наблюдения
descendants. `ProcessLifecycle` делегирует natural-exit wait и пока остается владельцем process-tree shutdown state
machine; эти части не раскрываются в пользовательском API.

`DefaultProtocolSession` остается владельцем lifecycle протокольной сессии, serialized request lock, transcript snapshot
и process exit snapshot. Внутренние детали чтения и записи разделены на маленькие владельцы:

- `ProtocolRequestWriter` владеет stdin writes, request deadline и request byte/char limits.
- `ProtocolOutputQueue` владеет bounded очередью между output pump и protocol reader.
- `ProtocolResponseReader` владеет deadline-aware чтением, framing helpers, text decoding и per-read limits.
- `ProtocolResponseBudget` владеет global response byte/char limits на один request.
- `ProtocolRuntimeFailures` является внутренней границей создания failures с transcript/process snapshot владельца
  сессии.

Публичное API не получает новых runtime-конструкторов или SPI из-за этой декомпозиции. Единственное публичное расширение
в этом решении — общий `ProcwrightException` как catch boundary для ошибок, произведенных Procwright; сценарные exception-типы
остаются основным источником structured details.

## Инварианты

- Public scenario API не раскрывает `ScenarioRuntime` или protocol implementation classes.
- One-shot I/O topology вычисляется один раз до launch, а первый terminal outcome после выбора не заменяется.
- One-shot result decoding не зависит от process lifecycle и сохраняет исходные captured bytes в success и typed
  decode-failure results.
- Provider operation timeout остается typed failure, а исчерпание внешнего lifecycle deadline становится `UNKNOWN`;
  ни `UNKNOWN`, ни `UNOBSERVABLE` не считаются доказательством выхода процесса.
- Наблюдавшиеся descendants переживают reparenting после выхода root и остаются доступны последующему cleanup.
- У каждого protocol limit есть один runtime-владелец: request limits у writer, response limits у reader/budget,
  backlog limit у queue.
- Failure taxonomy остается в публичных scenario-specific exceptions, а внутренние helpers только строят эти failures.
- Декомпозиция допустима, если имя класса и Javadoc объясняют его инвариант без знания истории проекта.

## Последствия

Плюсы:

- `CommandService` читается как API facade, а не как смесь API и runtime.
- `DefaultProtocolSession` больше не владеет одновременно queueing, decoding, request writing и limit accounting.
- Тесты могут проверять публичное поведение, не закрепляя внутреннюю форму runtime.

Минусы:

- `io.github.ulviar.procwright` как root package становится разрешенным dependency для subpackages из-за публичного
  `ProcwrightException`.
- Protocol internals остаются package-private и не являются SPI; пользовательская расширяемость идет через
  `ProtocolAdapter`.

## Проверка

- `ApiCompatibilityCheck` фиксирует `ProcwrightException` как часть exact public API baseline.
- `PackageBoundaryTest` допускает dependency на root package только как public error boundary.
- `ProcwrightExceptionTest` и `IntegrationExceptionTest` проверяют общий exception contract.
- `OneShotIoPlanTest`, `OneShotTerminationTest` и `OneShotResultAssemblerTest` проверяют one-shot topology, terminal
  arbitration и result assembly напрямую.
- `ProcessLauncherTest`, `ProcessLivenessTest`, `ProcessExitWaiterTest` и `LiveDescendantSnapshotTest` проверяют
  процессный launch, наблюдение, natural wait и snapshot без фиксации внутренностей shutdown-автомата.
- Protocol/session integration tests проверяют behavior через публичные сценарии, а не через internal classes.
