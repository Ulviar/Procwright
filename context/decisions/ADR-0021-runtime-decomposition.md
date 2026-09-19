# ADR-0021: Декомпозиция scenario runtime и protocol session

## Статус

Принято.

## Контекст

Сценарный API должен оставаться компактным: пользователь выбирает `run`, `interactive`, `lineSession`,
`protocolSession`, `listen` или pool-вариант, а не собирает runtime из низкоуровневых частей. При этом внутренний код не
должен концентрировать разные инварианты в одном большом классе, если границу ответственности можно описать ясно.

## Решение

`CommandService` остается публичным facade-объектом вокруг команды и сценарных entry points. `CommandSpec` является
единственным immutable владельцем общей launch-конфигурации. Persistent Draft хранит только scenario-specific settings.
`LaunchPlan.from(...)` является единственной границей материализации argv, environment, working directory и shell mode.
`ScenarioRuntime` строит execution/session plans из snapshot этих данных, открывает runtime wrappers, применяет readiness
и передает diagnostics дальше в runtime.

`ProcessKernel` является stateless service и хранит только зависимости one-shot runtime. Один вызов передается новому
`OneShotExecution`, который владеет всеми mutable ресурсами и фазами ровно одного запуска: подготовкой I/O, launch,
ожиданием terminal outcome, cleanup и сборкой результата. Immutable `OneShotIoPlan` переводит согласованный
`ExecutionPlan` в redirects, stdin action и необходимость I/O task executor до launch. `OneShotSupervision` выбирает первый
сигнал, прекращающий ожидание process: exit, timeout, stdin failure или ранний output capture failure.
`OneShotExecution` остаётся владельцем итогового outcome, потому что output capture после natural exit ещё может
завершиться failure. После cleanup `OneShotResultAssembler` декодирует завершенные captures и строит success либо typed
decode-failure `CommandResult`, сохраняя raw bytes в обоих случаях.
`OneShotDeadline` задаёт один absolute deadline для ожидания terminal outcome и обоих output captures: natural exit
root не маскирует timeout, если унаследованный pipe остаётся открыт после deadline.
`OwnedStreams` один раз получает стабильные ссылки на stdin/stdout/stderr, а каждый `OwnedStream` владеет своим
exact-once logical close. One-shot
cleanup ожидает обязательные I/O tasks и process-tree termination, но не ожидает поздний physical stream close:
последний выполняется best effort и не изменяет уже готовый result или failure.

Общий process runtime также разделен по наблюдаемым инвариантам. `ProcessLauncher` владеет launch обычного pipe process.
`ProcessLiveness` консервативно определяет, доказан ли выход обычного процесса, а для guarded operations различает
`LIVE`, `EXITED`, исчерпанный lifecycle budget `UNKNOWN` и недоступное OS/provider state `UNOBSERVABLE`. Два последних
состояния не доказывают выход. `ProcessExitWaiter` владеет caller-thread polling и wait deadline, а
`LiveDescendantSnapshot` накапливает bounded immutable snapshot живых либо временно недоступных для наблюдения
descendants. `ProcessLifecycle` является внутренним facade для natural-exit wait и shutdown. Единый
`ProcessTreeShutdown` оркестрирует graceful-to-forceful или force-only sequence; `ShutdownTreeState` владеет bounded
discovery, pending descendants и descendant proof state, `ProcessShutdownSignals` — порядком сигналов и bounded JDK
fallback, а `ShutdownFailureLedger` — failure identities, выбором primary и временным снятием/restoration interrupt
status. Итоговое решение о completion root и всего дерева после stabilization refresh принадлежит
`ProcessTreeShutdown`. Первое явное interruption становится primary; остальные source identities сохраняются в
плоском stable aggregate без изменения исходных `Throwable`. Эти части не раскрываются в пользовательском API.

`DefaultProtocolSession` остается владельцем lifecycle протокольной сессии, serialized request lock, transcript snapshot
и process exit snapshot. Внутренние детали чтения и записи разделены на маленькие владельцы:

- `ProtocolRequestWriter` владеет stdin writes, request deadline и request byte/char limits.
- `ProtocolOutputQueue` владеет bounded очередью между output pump и protocol reader.
- `ProtocolResponseReader` является request-scoped facade над raw и text operations.
- `ProtocolReadSource` владеет capability lifetime, deadline, terminal precedence и raw-byte access.
- `ProtocolTextReader` владеет complete-field и continuous text operations, включая persistent decoder state и
  транзакцию peek/decode/commit/rollback.
- `ProtocolTextFieldDecoder` владеет независимым декодированием byte-length-delimited text fields, decoder progress,
  replacement policy и per-field character limit.
- `ProtocolResponseBudget` владеет global response byte/char limits на один request.
- `ProtocolRuntimeFailures` является внутренней границей создания failures с transcript/process snapshot владельца
  сессии.

Публичное API не получает новых runtime-конструкторов или SPI из-за этой декомпозиции. Единственное публичное расширение
в этом решении — общий `ProcwrightException` как catch boundary для ошибок, произведенных Procwright; сценарные exception-типы
остаются основным источником structured details.

## Инварианты

- Public scenario API не раскрывает `ScenarioRuntime` или protocol implementation classes.
- `CommandSpec` является единственным общим launch snapshot, а `LaunchPlan.from(...)` — единственным местом его
  преобразования в готовую команду; scenario settings не копируют argv, environment или working directory.
- One-shot I/O topology вычисляется один раз до launch, а первый terminal outcome после выбора не заменяется.
- One-shot stream ownership не использует глобальную close capacity; physical stream close не является result gate.
- Mutable state двух one-shot запусков не может пересекаться: каждый запуск получает отдельный `OneShotExecution`.
- One-shot result decoding не зависит от process lifecycle и сохраняет исходные captured bytes в success и typed
  decode-failure results.
- Provider operation timeout остается typed failure, а исчерпание внешнего lifecycle deadline становится `UNKNOWN`;
  ни `UNKNOWN`, ни `UNOBSERVABLE` не считаются доказательством выхода процесса.
- Наблюдавшиеся descendants переживают reparenting после выхода root и остаются доступны последующему cleanup.
- Graceful и forceful shutdown остаются одной последовательностью фаз; tree state, signal policy и failure/interruption
  policy имеют разных владельцев.
- Успешный shutdown требует доказанного `EXITED` для root и известных descendants. Фаза с положительным wait budget
  также требует финального discovery до дедлайна; zero-wait phase ничего не ожидает и принимает уже наблюдённый выход.
  `UNKNOWN` и `UNOBSERVABLE` не доказывают завершение. В positive-wait фазах `stop()` один `WaitPhase` владеет
  post-signal deadline и для completion observation, и для следующего за ним exit-code snapshot. Zero-wait не создаёт
  окно ожидания, а force-only cleanup целиком остаётся внутри исходного operation deadline. Polling не начинает
  provider operation в последнем 10 ms кванте, но успешный выход всё равно требует stabilization с любым положительным
  остатком. Если времени на stabilization уже нет, текущая фаза завершается без успеха; forceful phase выполняет
  собственный scan до сигнала root. Как и у самого `ProcessHandle`, descendant, успевший reparenting до любого
  наблюдения, остаётся вне доказуемых гарантий runtime.
- `ProcessTreeScanner` различает `COMPLETE`, `LIMIT_REACHED` и `INCOMPLETE`: ровно limit допустим после доказанного
  исчерпания источника, следующий уникальный handle доказывает truncation, а unavailable или прерванный дедлайном scan
  остаётся incomplete. Operation owner сообщает deadline отдельно от unavailable provider state, а scanner сохраняет
  происхождение deadline: только фактически примененный caller budget дает `CALLER_DEADLINE`; внутренний
  `scanTimeout` дает `UNAVAILABLE`, а caller interruption — `INTERRUPTED`. Любой incomplete scan внутри уже начатого
  shutdown навсегда запрещает completion proof этого shutdown: следующий scan не может доказать отсутствие уже
  reparented процесса. Overflow также постоянен.
  Shutdown принимает known handles только как `KnownDescendants`: immutable insertion-ordered identity map, уже
  ограниченный общим descendant limit. Watcher сохраняет handles и sticky `LIMIT_REACHED`/`UNAVAILABLE`;
  `CALLER_DEADLINE` не отравляет следующий cleanup, а `INTERRUPTED` немедленно возвращается владельцу lifecycle.
  Cleanup handoff ждёт завершения активного watcher refresh и атомарно запечатывает snapshot, поэтому ни текущий, ни
  будущий refresh не публикует status или handle после handoff. Поэтому shutdown не индексирует handles повторно и не
  обходит произвольную caller-owned коллекцию до обязательного cleanup. Во всех случаях root и уже известные
  descendants получают cleanup-сигналы, но unavailable observation не выдаётся за успешный observable completion
  proof.
- Scanner и shutdown state считают уникальные процессы по `pid + startInstant`, а не по identity wrapper-объекта:
  повторное представление того же handle между scans и phases не расходует limit и не создаёт ложный overflow.
  `KnownDescendants` переносит уже вычисленную identity и исходный guarded owner в shutdown state без повторных
  provider calls. Обычный сбой traversal сохраняет уже обнаруженный prefix как incomplete scan. Fatal traversal
  переносит тот же prefix вместе с исходным `Error` и немедленно прекращает дальнейший graph traversal: cleanup сначала
  принимает и сигналит handles, затем возвращает одиночный `Error` без замены identity. Если закрытие traversal stream
  также завершилось ошибкой, scanner возвращает новый detached aggregate: исходный fatal остаётся primary, порядок
  failures сохраняется, а исходные `Throwable` не изменяются. После abandonment provider operation её поздний результат,
  включая embedded Error, игнорируется согласно ADR-0025; отдельного reporting settlement нет. Slot освобождается только
  после фактического возврата операции, поэтому повторные scans не создают неограниченное число зависших owners.
- У каждого protocol limit есть один runtime-владелец: request limits у writer, response limits у reader/budget,
  backlog limit у queue.
- Failure taxonomy остается в публичных scenario-specific exceptions, а внутренние helpers только строят эти failures.
- Декомпозиция допустима, если имя класса и Javadoc объясняют его инвариант без знания истории проекта.

## Последствия

Плюсы:

- `CommandService` читается как API facade, а не как смесь API и runtime.
- `ProcessKernel` не требует держать в голове lifecycle конкретного запуска: он только создает его владельца.
- `DefaultProtocolSession` больше не владеет одновременно queueing, decoding, request writing и limit accounting.
- Тесты могут проверять публичное поведение, не закрепляя внутреннюю форму runtime.

Минусы:

- `io.github.ulviar.procwright` как root package становится разрешенным dependency для subpackages из-за публичного
  `ProcwrightException`.
- Protocol internals остаются package-private и не являются SPI; пользовательская расширяемость идет через
  `ProtocolAdapter`.

## Проверка

- `PublicApiSurfaceTest` и external consumers фиксируют `ProcwrightException` как общий public error boundary.
- `PackageBoundaryTest` допускает dependency на root package только как public error boundary.
- `ProcwrightExceptionTest` и `IntegrationExceptionTest` проверяют общий exception contract.
- `CommandSpecTest` и `LaunchPlanTest` проверяют единый launch snapshot и его материализацию.
- `OneShotIoPlanTest`, `OneShotSupervisionTest` и `OneShotResultAssemblerTest` проверяют one-shot topology, process
  supervision и result assembly напрямую.
- `ProcessLauncherTest`, `ProcessLivenessTest`, `ProcessExitWaiterTest` и `LiveDescendantSnapshotTest` проверяют
  процессный launch, наблюдение, natural wait и snapshot без фиксации внутренностей shutdown-автомата.
- `ShutdownFailureLedgerTest`, `ShutdownTreeStateTest` и `ProcessShutdownSignalsTest` напрямую проверяют извлеченные
  shutdown-инварианты; `ProcessLifecycle*Test` остается сквозным контрактом всей последовательности фаз.
- Protocol/session integration tests проверяют behavior через публичные сценарии, а не через internal classes.
