# Карта доказательства инвариантов

## Назначение

Карта помогает быстро найти устойчивый инвариант, его главного владельца и исполняемое доказательство. Она не повторяет
все race conditions и имена test methods: эти детали принадлежат коду и тестам. Если правило нельзя кратко выразить и
связать с владельцем, архитектура требует дополнительной декомпозиции.

## API и конфигурация

### Сценарная грамматика

**Инвариант:** после `Procwright.command(...)` пользователь выбирает один сценарий; параллельного options-first API нет.

**Владелец:** `CommandService`.

**Proof:** `PublicApiSurfaceTest`, `ConsumerScenariosTest`.

### Draft semantics

**Инвариант:** Draft immutable, persistent и reusable.

**Владелец:** конкретная реализация выбранного scenario Draft.

**Proof:** `ScenarioDraftPersistenceIntegrationTest`, `ScenarioDraftReuseIntegrationTest`.

### Command snapshot

**Инвариант:** executable, argv, environment, working directory и shell mode образуют immutable snapshot; mutable argv
копируется до сохранения.

**Владелец:** `CommandSpec`.

**Proof:** `CommandSpecTest`, `RunLaunchConfigurationIntegrationTest`.

### Interactive branch

**Инвариант:** raw session и Expect выбираются до конфигурации scenario-specific readiness и до launch.

**Владелец:** `InteractiveScenario.Entry`.

**Proof:** `PublicApiSurfaceTest`, `ExpectDraftOwnershipIntegrationTest`.

### Pool branch

**Инвариант:** `pooled()` сохраняет immutable snapshot уже настроенного worker.

**Владелец:** pool-draft records в `LineSessionDrafts` и `ProtocolSessionDrafts`.

**Proof:** `ScenarioPoolDraftSemanticsIntegrationTest`, `ConsumerScenariosTest`.

### Public boundary

**Инвариант:** публичные signatures и JPMS exports не раскрывают internal или случайные external types.

**Владелец:** module descriptors и public package structure.

**Proof:** `PublicApiSurfaceTest`, `PackageBoundaryTest`.

### Nullness

**Инвариант:** экспортируемые Java packages имеют `@NullMarked`; nullable positions и static-transitive JSpecify
metadata являются частью публичного контракта.

**Владелец:** public `package-info.java`, type-use annotations и module descriptors.

**Proof:** `PublicNullnessContractTest`, `IntegrationNullnessMetadataTest`.

## Process lifecycle

### One-shot capture

**Инвариант:** capture ограничен, отдельно сообщает truncation и сохраняет raw bytes при успешном и ошибочном decoding.

**Владелец:** `OneShotResultAssembler`.

**Proof:** `OneShotResultAssemblerTest`, `RunCaptureIntegrationTest`.

### One-shot completion

**Инвариант:** один absolute deadline покрывает stdin, process wait и output drain; первый supervision signal запускает
cleanup, а блокирующий physical close не переписывает и не задерживает готовый result.

**Владелец:** `OneShotExecution`.

**Proof:** `OneShotSupervisionTest`, `RunTimeoutCleanupIntegrationTest`.

### One-shot execution isolation

**Инвариант:** stdin/output tasks одного execution не блокируют запуск независимого execution через глобальную квоту.

**Владелец:** `OneShotExecution`.

**Proof:** `ProcessKernelIoFailureAndInputTest`.

### One-shot task failure

**Инвариант:** фактическая I/O failure остаётся наблюдаемой после отмены task `Future`.

**Владелец:** `OneShotTask`.

**Proof:** `OneShotTaskTest`.

### Process launch

**Инвариант:** согласованные argv, environment, working directory и redirects применяются один раз; launch failure
сохраняет typed reason и redacted command summary.

**Владелец:** `ProcessLauncher`.

**Proof:** `ProcessLauncherTest`, `RunLaunchConfigurationIntegrationTest`.

### Process liveness

**Инвариант:** `UNKNOWN` и `UNOBSERVABLE` не считаются доказательством выхода процесса.

**Владелец:** `ProcessLiveness`.

**Proof:** `ProcessLivenessTest`, `GuardedProcessExitWaiterTest`.

### Natural exit wait

**Инвариант:** natural wait выполняется на caller thread, соблюдает lifecycle deadline и не передаёт управление
произвольному provider `waitFor`.

**Владелец:** `ProcessExitWaiter`.

**Proof:** `ProcessExitWaiterTest`, `GuardedProcessExitWaiterTest`.

### Descendant knowledge

**Инвариант:** наблюдавшиеся descendants сохраняются как bounded identity snapshot; incomplete или unavailable
наблюдение остаётся недостаточным для доказательства полного cleanup.

**Владелец:** `KnownDescendants`.

**Proof:** `KnownDescendantsTest`, `LiveDescendantSnapshotTest`.

### Process-tree scan

**Инвариант:** scan bounded, дедуплицирован по process identity и различает complete, limit-reached, deadline,
interruption и provider failure; найденный prefix не теряется.

**Владелец:** `ProcessTreeScanner`.

**Proof:** `ProcessTreeScannerTraversalTest`, `ProcessTreeScannerOperationOwnershipTest`.

### Process-tree shutdown

**Инвариант:** graceful и forceful phases используют общее консервативное состояние; успех требует доказанного выхода
root и известных descendants, а fatal traversal не лишает cleanup уже найденный prefix. Временный incomplete последнего
combined refresh блокирует completion; следующий полный refresh может снять его. Access failures, overflow и известные
handles сохраняются. Необнаруженный detached descendant остаётся за границей best-effort cleanup.

**Владелец:** `ProcessTreeShutdown`.

**Proof:** `ShutdownTreeStateTest`, `ProcessLifecycleCompletionProofTest`.

### Shutdown failure retention

**Инвариант:** повторные observation failures удерживают не более 32 источников и отдельно первое interruption после
заполнения лимита; выбранная primary и восстановление interrupt status сохраняются без изменения исходных exceptions.
Ограничение diagnostic detail не прекращает cleanup.

**Владелец:** `ShutdownFailureLedger`.

**Proof:** `ShutdownFailureLedgerTest`, `ProcessLifecycleCompletionProofTest`.

### Destroy fallback capacity

**Инвариант:** fallback-вызовы потенциально блокирующих `Process.destroy*` используют общую bounded capacity; при её
исчерпании новый disposable thread не создаётся.

**Владелец:** `BoundedDestroyDispatcher`.

**Proof:** `BoundedDestroyDispatcherTest`, `ProcessTreeShutdownFailureContinuationTest`.

### Failure ownership

**Инвариант:** первое обязательное failure или interruption остаётся primary; secondary failures не изменяют исходные
`Throwable` и не заменяют выбранный lifecycle outcome.

**Владелец:** `FailureAggregation`.

**Proof:** `FailureAggregationTest`, `ShutdownFailureLedgerTest`.

### Session construction

**Инвариант:** partial session construction не публикует watcher до commit; любой rollback закрывает все уже полученные
ресурсы, а live resources не резервируют bounded close capacity.

**Владелец:** `SessionConstruction`.

**Proof:** `SessionConstructionTest`, `DefaultSessionConstructionTest`.

### Output ownership

**Инвариант:** raw, line, protocol, Expect и stream output modes выбираются до launch и получают ровно одного consumer;
helper pumps должны быть готовы до открытия exit-watcher gate.
Natural exit line/protocol/Expect ждёт drain; timeout внешнего ожидания future не забирает output ownership.
Явный close после root exit завершает logical drain даже при оставшемся открытом pipe.

**Владелец:** `SessionOutputOwnership`.

**Proof:** `SessionOutputOwnershipStateTest`, `SessionRuntimeTest`, `SessionOutputOwnerLifecycleTest`.

### Process I/O ownership

**Инвариант:** logical close exact-once; paired output close атомарен относительно single close; raw output после
natural exit остаётся доступен до явного закрытия владельцем.

**Владелец:** `ProcessStreamResource` и `ProcessIoResources`.

**Proof:** `ProcessStreamResourceTest`, `ProcessIoBundleTest`.

### Public stdin close

**Инвариант:** `Session.closeStdin()` атомарно закрывает public write capability ровно один раз и не ждёт concurrent
writer; после logical close новые write и flush отклоняются.

**Владелец:** `SessionResources`.

**Proof:** `DefaultSessionStdinCloseContentionTest`, `DefaultSessionStdinCloseFailurePropagationTest`.

### Session failure settlement

**Инвариант:** failure физического close или helper path входит в общий terminal lifecycle; первый primary claim владеет
process cleanup, а проигравший close не меняет outcome и не присоединяется к cleanup owner. Close после natural exit без
primary owner отдельно claims единственный post-outcome cleanup caller-owned raw resources и известных descendants.

**Владелец:** `DefaultSession`.

**Proof:** `DefaultSessionStdinCloseTerminalRaceTest`, `DefaultSessionDescendantCleanupFailureTest`,
`SessionRawOutputOwnershipContractTest`.

### Session cleanup

**Инвариант:** конкурирующие session stop/failure paths запускают process-tree cleanup не более одного раза и
публикуют один exit-code snapshot.

**Владелец:** `SessionProcessCleanup`.

**Proof:** `SessionProcessCleanupTest`, `DefaultSessionDescendantCleanupFailureTest`.

### Readiness

**Инвариант:** readiness выполняется после launch, но до возврата handle или помещения worker в idle; failure закрывает
созданный процесс.

**Владелец:** `ReadinessSupport`.

**Proof:** `ReadinessSupportTest`, `PooledProtocolSessionWarmupIntegrationTest`.

### Timed callbacks

**Инвариант:** один task атомарно переходит из `PENDING` в `RUNNING` или `ABANDONED`. Timeout или cancellation,
выигравшие до `RUNNING`, не допускают входа в callback; после abandonment caller не ждёт поздний outcome.

**Владелец:** `TimedTaskRunner`; cancellation signal допускает одну активную регистрацию. Её снятие проверяет
identity регистрации, чтобы старый callback не мог удалить новую подписку.

**Proof:** `TimedTaskRunnerTest`.

### Provider operations

**Инвариант:** process-provider operations имеют общую ограниченную capacity, deadline и disposable daemon owner;
timeout не освобождает capacity до фактического завершения операции. Interruption достигает уже запущенного worker,
в том числе до входа в callback. Поздний result не меняет выбранный outcome; reporting settlement не является gate.

**Владелец:** `ProcessProviderOperationOwner`.

**Proof:** `ProcessProviderOperationOwnerTest`, `ProcessTreeScannerOperationOwnershipTest`.

### Timeout policy

**Инвариант:** `Duration.ZERO` отключает только поддерживающий это timeout; отрицательное значение отклоняется до
launch.

**Владелец:** `DurationSupport`.

**Proof:** `DurationSupportTest`, `RunTimeoutIntegrationTest`.

### Charset policy

**Инвариант:** strict decoding сообщает malformed input, replacement decoding разрешается только явной policy, а raw
bytes остаются доступны там, где сценарий их возвращает.

**Владелец:** `CharsetPolicy`.

**Proof:** `CharsetPolicyTest`, `RunCharsetPolicyIntegrationTest`.

### PTY boundary

**Инвариант:** terminal policy выбирается явно; provider получает immutable `PtyRequest`, а system wrapper не
раскрывается через SPI.

**Владелец:** `ProcessTransport`.

**Proof:** `PtyRequestTest`, `SystemPtyLaunchBoundaryIntegrationTest`.

## Session protocols

### Shared terminal outcome

**Инвариант:** process settlement является fallback, первое non-exit failure имеет приоритет, а public exit ждёт
logical settlement выбранного output mode, но не блокирующий physical close.

**Владелец:** `SessionTerminal`.

**Proof:** `SessionTerminalTest`, `DefaultLineSessionExitContractTest`.

### Output cleanup ordering

**Инвариант:** после process outcome и mode settlement output owner передаёт пару stdout/stderr на physical close
ровно один раз. Ни ранний EOF одной pump, ни закрытие её wrapper-а не разрешают premature close. Завершение обеих
pump tasks либо logical abandonment достаточно; отдельные per-stream уведомления не нужны.

**Владелец:** `OutputPumpCleanup`; pair admission и single physical close принадлежат `ProcessStreamResource`.

**Proof:** `OutputPumpCleanupCoordinationTest`, `OutputPumpStartupTransactionTest`, `SessionOutputOwnerLifecycleTest`.

### Line request transaction

**Инвариант:** line requests сериализованы; validation, request-size check, encoding, write и decode разделяют один
deadline, а ожидающий request не пишет в stdin и не захватывает response state.

**Владелец:** `DefaultLineSession`.

**Proof:** `DefaultLineSessionRequestAdmissionTest`, `LineSessionSerializationAndDeadlinesIntegrationTest`.

### Line write handoff

**Инвариант:** request failure retry-safe только до доказанного handoff в stdin writer; после handoff timeout,
interruption или write failure становятся terminal из-за неопределённости доставки.

**Владелец:** `LineRequestWriter`.

**Proof:** `TimedTaskRunnerTest`, `DefaultLineSessionWriterFailureTest`.

### Line terminal state

**Инвариант:** active request, close и terminal failure имеют одного арбитра; позднее failure не меняет уже выбранный
outcome, typed reason сохраняется для последующих requests, а transcript не строится под state monitor. Request и
output pump передают в cleanup одну выбранную первичную причину: `onExit()` не подменяет исходную ошибку декодера
обёрткой request exception в зависимости от порядка потоков.

**Владелец:** `LineSessionState`.

**Proof:** `LineSessionStateTest`, `LineSessionBacklogAndTerminalIntegrationTest`,
`DefaultLineSessionOutputDecodingTest`, `LineSessionDecoderSafetyIntegrationTest`.

### Line backlog

**Инвариант:** line backlog ограничен lines/chars и unfinished-line limit и корректно обрабатывает LF/CRLF.

**Владелец:** `LineOutputTransport`.

**Proof:** `LineOutputTransportTest`, `LineSessionBacklogAndTerminalIntegrationTest`.

### Incremental text decoding

**Инвариант:** text decoding не допускает rewind, unbounded pending state или частичную публикацию результата после
malformed input.

**Владелец:** `IncrementalTextDecoder`.

**Proof:** `IncrementalTextDecoderFailureAtomicityTest`, `LineSessionDecoderSafetyIntegrationTest`.

### Protocol request transaction

**Инвариант:** serialization, adapter write, response decode и ожидание admission входят в один request deadline;
partial write, timeout после admission или проглоченное adapter-ом I/O failure закрывают session. Timeout ожидания
serialized slot не допускает write и сохраняет session для следующего request.

**Владелец:** `DefaultProtocolSession`.

**Proof:** `ProtocolSessionRequestAdmissionAndSerializationTest`,
`ProtocolRequestAdmissionAndDeadlineIntegrationTest`.

### Protocol request framing

**Инвариант:** adapter определяет request framing, а runtime применяет request byte/char limits и не позволяет
adapter-у проглотить partial-write или limit failure.

**Владелец:** `ProtocolRequestWriter`.

**Proof:** `ProtocolSessionCallbackCapabilityAndFramingTest`, `ProtocolRequestFramingAndLimitsIntegrationTest`.

### Protocol response budget

**Инвариант:** response byte/char limits глобальны для всего request и всех последовательных adapter reads.

**Владелец:** `ProtocolResponseBudget`.

**Proof:** `ProtocolResponseReaderExactTextTest`, `ProtocolResponseFramingAndLimitsIntegrationTest`.

### Protocol terminal state

**Инвариант:** active request, close и terminal failure имеют одного арбитра; normal close фиксируется отдельным
монотонным ownership flag и не создаёт failure snapshot. Snapshots принадлежат только failure/fatal outcomes, typed
reason сохраняется для последующих requests, а transcript и exit-code lookup не выполняются под state monitor.

**Владелец:** `ProtocolSessionState`.

**Proof:** `ProtocolSessionStateTest`, `ProtocolTerminalOutcomeIntegrationTest`.

### Request capabilities

**Инвариант:** callback reader/writer живёт только в своей request phase и thread; abandonment не позволяет позднему
callback заменить timeout или cancellation.

**Владелец:** `RequestCapabilityScope`.

**Proof:** `RequestCapabilityScopeTest`, `ProtocolSessionCallbackCapabilityAndFramingTest`.

### Protocol adapter isolation

**Инвариант:** каждый direct session и pool worker получает отдельный adapter до process launch; factory может
вызываться конкурентно.

**Владелец:** `ScenarioRuntime.createProtocolAdapter`.

**Proof:** `ProtocolAdapterFactoryIntegrationTest`, `ScenarioDraftReuseIntegrationTest`.

### Expect retention

**Инвариант:** retained transcript и searchable match window имеют независимые bounds; cursor остаётся корректным при
удалении старого prefix.

**Владелец:** `ExpectSessionState`.

**Proof:** `ExpectIntegrationTest`, `DefaultExpectCursorMatchingTest`.

### Expect regex isolation

**Инвариант:** regex evaluation использует deadline и локальную сериализацию handle; abandoned matcher не может
заменить выбранный terminal outcome или допустить следующий callback на том же handle.

**Владелец:** `ExpectRegexMatcher`.

**Proof:** `ExpectRegexMatcherTest`, `DefaultExpectMatcherAdmissionTest`.

### Expect

**Инвариант:** matching не меняет stdin/transcript/cursor после invalid или terminal operation; output публикуется
incrementally, а terminal outcomes разрешаются first-terminal-wins. Обычный timeout ожидания вывода остаётся
recoverable: повторная regex evaluation начинается только после изменения output или cursor. Изменение cursor будит
ожидающий matcher даже без нового output.

**Владелец:** `ExpectSessionState`.

**Proof:** `ExpectSessionStateTest`, `ExpectIntegrationTest`.

### Streaming

**Инвариант:** stream не удерживает весь output, применяет синхронный serialized backpressure и публикует один outcome;
natural exit ждёт logical drain, explicit close/timeout может abandon некооперативный listener.

**Владелец:** `DefaultStreamSession`.

**Proof:** `DefaultStreamSessionTerminalClaimTest`, `StreamRuntimeTerminalLifecycleTest`.

## Pool

### Runtime reuse

**Инвариант:** pool повторно использует существующий line/protocol runtime и не создаёт второй process engine или
public lease API.

**Владелец:** `DefaultPooledLineSession` и `DefaultPooledProtocolSession`.

**Proof:** `PublicApiSurfaceTest`, `PooledProtocolSessionRequestIntegrationTest`.

### Worker partition

**Инвариант:** worker принадлежит ровно одному состоянию `STARTING`, `IDLE`, `LEASED` или `RETIRING`; idle queue
является индексом, а не вторым источником состояния.

**Владелец:** `PoolPartition`.

**Proof:** `PoolPartitionTest`, `WorkerPoolStateTest`.

### Pool size policy

**Инвариант:** pool size, warmup и idle-floor values валидируются совместно до открытия pool.

**Владелец:** `WorkerPoolSettings`.

**Proof:** `PolicyValueTest`, `WorkerPoolPolicyTest`.

### Runtime capacity

**Инвариант:** `maxSize` учитывает workers во всех состояниях одного pool и не создаёт process-global quota; warmup и
`minIdle` используют только capacity своего pool.

**Владелец:** `WorkerPoolState`.

**Proof:** `WorkerPoolControllerCapacityTest`, `WorkerStartupCoordinatorTest`.

### Startup slot settlement

**Инвариант:** timeout/interruption удерживает `STARTING` slot до late completion; close отделяет slot, а late result
не возвращается в partition.

**Владелец:** `WorkerPoolState`.

**Proof:** `WorkerPoolStateTest`, `WorkerPoolControllerStartupRaceTest`.

### Worker startup

**Инвариант:** один `WorkerStartup` запускает не более одного factory callback, выбирает один typed terminal outcome и
передаёт проигравший late result ровно один раз. Последующие close, deadline или interruption не переписывают outcome;
interruption вызывающего потока сохраняет interrupt flag. Coordinator отображает выбранный outcome без второго выбора.

**Владелец:** `WorkerStartup`.

**Proof:** `WorkerStartupTest`, `WorkerStartupCoordinatorTest`, `WorkerPoolControllerStartupRaceTest`.

### Worker retirement

**Инвариант:** retirement создаётся только для принятой session, запускает close ровно один раз и освобождает capacity
только после logical settlement.

**Владелец:** `WorkerRetirement` — exact-once initiation и стабильный outcome; `WorkerCloseSupport` — совместное
ожидание возврата close и terminal observation; `WorkerRetirementCoordinator` — batch orchestration вне pool monitor.

**Proof:** `WorkerRetirementTest`, `WorkerCloseSupportTest`, `WorkerRetirementCoordinatorTest`,
`PooledWorkerRetirementCoordinationTest`.

### Replenishment

**Инвариант:** положительный `minIdle` поддерживается одной pending attempt с bounded backoff; close отменяет
незавершённую работу пополнения.

**Владелец:** `PoolReplenisher`: один monitor для pending/running attempt, позднего cancellation handle и stop.
Fatal failure закрывает admission до внешнего failure handler и не допускает повторного запуска.

**Proof:** `PoolReplenisherTest`, `WorkerPoolControllerReplenishmentTest`.

### Pooled request

**Инвариант:** acquire и request timeout различимы; request orchestration ровно один раз release-ит или retire-ит
worker и не раскрывает его наружу. Line preflight отклоняет невалидный ввод до acquire; encoded array создаётся после
acquire. Локальный failure подготовки до session request возвращает незатронутый worker без reset и без расходования
его request limit.

**Владелец:** `PooledRequestRunner` — lease lifecycle, `LineRequestEncoder` — проверка и кодирование line input,
`DefaultPooledLineSession` — request budget без acquire wait.

**Proof:** `PooledRequestPreparationTest`, `WorkerPoolControllerAcquisitionTest`,
`PooledLineSessionRequestIntegrationTest`, `PooledProtocolSessionRequestIntegrationTest`.

### Worker hooks

**Инвариант:** заданные reset и health hooks bounded, не перекрываются с request на одном worker и сохраняют `Error`,
metrics и retirement reason. Отсутствующие hooks не запускают timed task и не зависят от hook timeout.

**Владелец:** `WorkerHookSupport` — выполнение callback; `WorkerPoolSettings` — явное отсутствие hooks;
`DefaultPooledLineSession` и `DefaultPooledProtocolSession` — пропуск отсутствующего callback.

**Proof:** `PolicyValueTest`, `WorkerHookSupportTest`, `PooledLineSessionWorkerHooksIntegrationTest`,
`PooledProtocolSessionWorkerHooksIntegrationTest`.

### Pool terminal decision

**Инвариант:** construction разрешается ровно один раз; terminal future получает не более одного drain publication
только после начала close и выхода всех logical workers, причём публикация выполняется вне pool monitor.

**Владелец:** `PoolTermination`.

**Proof:** `PoolTerminationTest`, `WorkerPoolStateTest`.

### Public pool close

**Инвариант:** synchronous `close()` имеет deadline и typed timeout/interruption/failure mapping; `closeAsync()`
изолирует consumer cancellation.

**Владелец:** `PoolCloseSupport`.

**Proof:** `PoolCloseSupportTest`, `WorkerPoolControllerCloseIsolationTest`.

### Pool failure routing

**Инвариант:** construction, close и late worker failures либо участвуют в ещё не выбранном terminal outcome, либо
превращаются в `FailureReport`; уже выбранный drain outcome не меняется.

**Владелец:** `PoolTermination`.

**Proof:** `PoolTerminationTest`, `WorkerPoolStateTest`.

### Pool failure publication

**Инвариант:** каждый `FailureReport` отправляется одному bounded best-effort reporter с сохранённым адресатом.
Submission не ждёт доставки или свободного места; насыщение, зависший адресат и failure reporting infrastructure
не задерживают lifecycle и не заменяют terminal outcome. Доставка каждого уведомления не гарантируется.

**Владелец:** `PoolFailurePublisher` — submission и изоляция failure; `BoundedFailureReporter` — admission и delivery.

**Proof:** `PoolFailurePublisherTest`, `BoundedFailureReporterTest`, `WorkerPoolControllerConstructionTest`,
`WorkerPoolControllerRetirementTest`.

### Pool retirement dispatch

**Инвариант:** обязательный retirement использует fixed owner set и bounded queue; при насыщении выполняется caller-ом,
а не теряется. Retirement не зависит от доставки best-effort failure notifications.

**Владелец:** `PoolLifecycleDispatcher`.

**Proof:** `PoolLifecycleDispatcherTest`, `PoolFailurePublisherTest`.

### Pool metrics

**Инвариант:** current counts происходят только из partition, cumulative durations/failures/reasons — из metrics
ledger; snapshot не вызывает пользовательский callback под pool monitor.

**Владелец:** `PoolMetrics`.

**Proof:** `PoolMetricsTest`, `WorkerPoolControllerMetricsTest`.

## Diagnostics и optional modules

### Diagnostics

**Инвариант:** diagnostics bounded, schema-valid, best-effort и не меняет outcome; события одного lifecycle
упорядочены для каждого recipient и связаны `runId`.

**Владелец:** `DiagnosticEmitter`.

**Proof:** `DiagnosticEmitterTest`, `DiagnosticsIntegrationTest`.

### Kotlin

**Инвариант:** Kotlin API сохраняет Java Draft semantics, а coroutine cancellation соблюдает ownership direct
sessions, pooled requests и stream collectors. Отменённый caller не получает значение completed future и отменяет
только своё pending view, сохраняя shared session state. Flow закрывает принадлежащую collection сессию ровно один раз;
secondary cleanup failure не подменяет первичную failure или cancellation и не мутирует исходный `Throwable`.

**Владелец:** `:procwright-kotlin`.

**Proof:** `PublicKotlinApiSurfaceTest`, `CoroutineExtensionsTest`, `StreamFlowTest`.

### Integrations

**Инвариант:** adapters используют core runtime, не добавляют process engine и отклоняют malformed JSON, UTF-8,
headers и oversized frames до domain mapping.

**Владелец:** `:procwright-integrations`.

**Proof:** `ProtocolAdaptersTest`, `externalLibraryBoundaryCheck`.

## Release proofs

Состав быстрых, сценарных, stress и memory gates принадлежит
[test tiers](../evals/test-tiers.md). Publication, documentation, platform matrix и isolated-consumer proofs
принадлежат [publication readiness](../release/publication-readiness.md).

Новый behavior должен расширять существующий элемент или добавлять новый. Точный race и method-level proof остаются в
тесте; карта хранит только правило, которое должно пережить внутренний refactoring.
