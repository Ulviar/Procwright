# Карта доказательства инвариантов

## Назначение

Карта связывает устойчивый инвариант с единственным владельцем и наблюдаемым proof. Внутренний класс можно менять,
если новый владелец сохраняет тот же контракт и проверки. Test доказывает правило, но не становится его владельцем.

## API и normalization

| Инвариант | Владелец | Доказательство |
| --- | --- | --- |
| Пользователь выбирает сценарий после `Procwright.command(...)`; параллельного options-first dialect нет. | `CommandService`, scenario `Draft`/`PoolDraft`. | `CommandServiceTest`, `PublicApiSurfaceTest`, `ScenarioEntrypointIntegrationTest`. |
| Scenario Draft immutable, persistent, defensive-copying и reusable; процесс создается только terminal method. | Реализации `RunScenario.Draft`, `InteractiveScenario.Draft`, `LineSessionScenario.Draft`, `ProtocolSessionScenario.Draft`, `StreamScenario.Draft`. | Draft unit tests, `ScenarioDraftSemanticsIntegrationTest`, consumer compilation gate. |
| `Session.expect()` возвращает unopened persistent Draft; output ownership захватывает только `open()`. | `ImmutableExpectDraft`, `SessionOutputOwnership`. | `PublicApiSurfaceTest`, `ExpectIntegrationTest`. |
| Worker settings фиксируются до `pooled()`, pool settings не зависят от порядка `with*`. | `LineSessionScenario.PoolDraft`, `ProtocolSessionScenario.PoolDraft`, `WorkerPoolSettings`. | scenario Draft tests, pooled integration tests. |
| Runtime получает валидированный immutable plan. | Internal settings, `ExecutionPlan`, `SessionExecutionPlan`, `StreamExecutionPlan`, `ScenarioRuntime`. | settings/policy tests и integration tests всех сценариев. |
| Direct argv — default, shell mode явный, environment inheritance выбирается policy. | `CommandSpec`, `LaunchSettings`, `EnvironmentPolicy`, `SystemShell`. | `CommandSpecTest`, scenario Draft tests, `OneShotExecutionIntegrationTest`. |
| Public API не раскрывает internal/external types и не растет без решения. | JPMS descriptors, exact signature baseline, package boundary checks и проверка direct/resolved runtime dependencies публичных модулей. | `PublicApiSurfaceTest`, `PackageBoundaryTest`, `externalLibraryBoundaryCheck`, `apiCompatibilityCheck`. |
| Экспортируемые Java packages core/integrations имеют `@NullMarked`; допустимые `@Nullable`/`UNION_NULL` positions и `requires static transitive org.jspecify` входят в compatibility surface. | Public `package-info.java`, explicit type-use annotations, JPMS descriptors и `compileOnlyApi` publication configuration. | [`PublicNullnessContractTest`](../../src/test/java/io/github/ulviar/procwright/PublicNullnessContractTest.java), [`IntegrationNullnessMetadataTest`](../../procwright-integrations/src/test/java/io/github/ulviar/procwright/integration/IntegrationNullnessMetadataTest.java), `kotlinJSpecifyStrictnessCheck` и `apiCompatibilityCheck`. |

## Process, I/O и lifecycle

| Инвариант | Владелец | Доказательство |
| --- | --- | --- |
| One-shot capture bounded, сообщает truncation и сохраняет raw bytes при decode failure. | `CapturePolicy`, `CapturedOutput`, `OneShotTextDecoder`, `CommandResult`. | `CapturedOutputTest`, `CommandResultTest`, hostile-decoder integration cases, stress tests. |
| Timeout/close/failure останавливает process tree; зависающий destroy использует общую bounded capacity без fallback threads. | `ShutdownPolicy`, `ProcessLifecycle`, `BoundedDestroyDispatcher`, `LateTaskFailureReporter`. | `ProcessLifecycleTest`, `BoundedDestroyDispatcherTest`, timeout integration/stress cases. |
| Все cleanup phases выполняются независимо; первый failure сохраняется, остальные подавляются без циклов. | `SuppressionSupport`, runtime lifecycle owners. | `SuppressionSupportTest`, `ProcessKernelLifecycleTest`, session lifecycle tests. |
| Raw stdin close не блокируется на concurrent writer и использует bounded capacity. | [`DefaultSession`](../../src/main/java/io/github/ulviar/procwright/internal/session/DefaultSession.java), [`BoundedCloseDispatcher`](../../src/main/java/io/github/ulviar/procwright/internal/BoundedCloseDispatcher.java), session stdin state. | [`DefaultSessionLifecycleTest`](../../src/test/java/io/github/ulviar/procwright/internal/session/DefaultSessionLifecycleTest.java), [`OutputCloseDispatcherTest`](../../src/test/java/io/github/ulviar/procwright/internal/session/OutputCloseDispatcherTest.java), blocked-pipe integration cases. |
| Output ownership нельзя разделить между raw readers и helpers/pumps. | `SessionOutputOwnership`, guarded stream wrappers. | `SessionOutputOwnershipTest`, expect/line/stream integration tests. |
| Readiness проходит после launch, но до возврата session или idle worker. | `ReadinessSupport`, `ScenarioRuntime`, scenario `ReadinessSettings`. | line/protocol readiness integration tests и canonical readiness example. |
| Stream listeners, readiness probes и worker hooks не исчерпывают capacity друг друга; зависшая bounded operation удерживает только свое разрешение. Произвольные callbacks не переиспользуют потоки между owners; stream chunks используют session-affine owner с replacement/rejection settlement; trusted process scanner переиспользует не более 32 sanitized owners и не публикует ожидаемый checked interrupt после abandonment. | `BoundedTaskRunner`, `StreamListenerTaskOwner`, независимые callback limiters, `ProcessTreeScanner.OperationOwner`. | `BoundedCallbackIsolationTest`, `BoundedTaskRunnerTest`, `StreamListenerTaskOwnerTest`, `ProcessTreeScannerTest`, `ReadinessSupportTest`, `WorkerHookSupportTest`. |
| Caller interruption восстанавливает interrupt status и выполняет cleanup. | `ProcessLifecycle`, line/protocol request runtime. | interrupted run/request integration tests. |
| `Duration.ZERO` отключает поддерживающий это timeout; отрицательное значение отклоняется до запуска. | `DurationSupport`, соответствующие internal settings и lifecycle wait. | duration/settings tests, zero/negative timeout integration tests. |
| File/discard capture использует OS redirect и проверяет совместимость с output mode до запуска. | `CapturePolicy`, `ExecutionPlan`, `StdioConfig`. | policy tests и file/discard one-shot integration cases. |
| PTY transport получает immutable resolved request, выбирается только terminal policy и не раскрывает system wrapper за provider SPI. | [`PtyRequest`](../../src/main/java/io/github/ulviar/procwright/terminal/PtyRequest.java), [`ProcessTransport`](../../src/main/java/io/github/ulviar/procwright/internal/ProcessTransport.java), [`SystemPtyProvider`](../../src/main/java/io/github/ulviar/procwright/terminal/SystemPtyProvider.java). | [`PtyRequestTest`](../../src/test/java/io/github/ulviar/procwright/terminal/PtyRequestTest.java), [`SystemPtyProviderTest`](../../src/test/java/io/github/ulviar/procwright/terminal/SystemPtyProviderTest.java), [`PtyTransportIntegrationTest`](../../src/integrationTest/java/io/github/ulviar/procwright/PtyTransportIntegrationTest.java). |

## Session protocols

| Инвариант | Владелец | Доказательство |
| --- | --- | --- |
| Line request сериализован; validation/encoding/write/decode входят в один deadline. | `DefaultLineSession`, `LineRequestEncoder`, `BoundedTaskRunner`. | `LineSessionIntegrationTest`, `BoundedTaskRunnerTest`, pooled line tests. |
| Line backlog ограничен числом lines и chars; unfinished line имеет отдельный limit и корректную LF/CRLF семантику. | Line output queue/decoder в `DefaultLineSession`. | `PolicyValueTest`, line backlog/newline integration cases. |
| Incremental line/protocol decoder не допускает rewind, overflow без progress и unbounded pending state. | `IncrementalTextDecoder`, `ProtocolTextDecoderState`. | decoder unit tests и hostile-decoder session integration cases. |
| Protocol adapter владеет framing; runtime владеет request serialization, deadline, transcript и failure taxonomy. | `DefaultProtocolSession`, `ProtocolRequestWriter`, `ProtocolResponseReader`, `ProtocolResponseBudget`. | `ProtocolSessionIntegrationTest`, reader/writer/budget unit tests. |
| Callback I/O capability не переживает свой request phase и не используется с другого thread. | `RequestCapabilityScope`, line/protocol readers и protocol writer. | `RequestCapabilityScopeTest`, escaped reader/writer unit tests. |
| После abandonment late `RuntimeException`/`Error` line и protocol callback публикуется ровно один раз асинхронно и только после возврата callback permit; owner-induced checked cancellation не публикуется. | `BoundedTaskRunner`, `BoundedFailureReporter`, line/protocol session runtime. | `BoundedTaskRunnerTest`, `DefaultLineSessionTest`, `DefaultProtocolSessionTest`. |
| Response byte/char budget глобален для всего request, включая несколько adapter reads. | `ProtocolResponseBudget`. | protocol multi-read limit tests. |
| Каждый direct session и pool worker получает отдельный adapter до запуска процесса; concurrent terminals могут вызывать thread-safe factory одновременно. | Protocol Draft и `ScenarioRuntime.createProtocolAdapter`. | protocol factory/repeated/concurrent open и pool integration tests. |
| Непрочитанный хвост chunk сохраняется между request-scoped readers и правильно учитывается в backlog. | `ProtocolOutputQueue`. | `ProtocolOutputQueueTest`, `ProtocolResponseReaderTest`. |
| Первый terminal session failure сохраняет stable reason для последующих requests. | Terminal-failure state line/protocol runtime. | timeout/backlog/process-exit follow-up request tests. |
| Expect matching bounded; actions редактируются по умолчанию, match result остается живым output. | `ExpectSettings`, `ExpectTranscriptValues`, `DefaultExpect`, `ExpectMatch`. | `ExpectMatchTest`, `ExpectIntegrationTest`. |
| Streaming применяет backpressure, не хранит весь output и различает listener/read/process failures. | `StreamSettings`, `DefaultStreamSession`, `StreamRuntime`, `StreamException.Reason`. | `StreamScenarioIntegrationTest`, `StreamRuntimeTest`, stress tests. |

## Pool

| Инвариант | Владелец | Доказательство |
| --- | --- | --- |
| Pool использует существующий line/protocol runtime и не раскрывает lease. | Nested `PoolDraft`, `DefaultPooledLineSession`, `DefaultPooledProtocolSession`. | public surface, scenario entrypoint и pooled integration tests. |
| Worker принадлежит ровно одному live state; `maxSize` учитывает starting/idle/leased/retiring. | `WorkerPoolController`. | `WorkerPoolControllerTest`, contention stress tests. |
| Startup, retirement и replenishment bounded; partial construction failure закрывает созданных workers. | `ConstructionLedger`, `RetirementScheduling`, `PoolRetirementDispatcher`, `WorkerCloseSupport`. | pool controller/unit tests, warmup/replenishment/saturation integration tests. |
| Acquire и request timeout различимы; failed request retire-ит worker. | Pool controller и pooled exception reasons. | pooled line/protocol timeout tests. |
| Reset/health hooks bounded; `Error` не теряется, response accounting и retire reason остаются корректными. | `WorkerHookSupport`, `WorkerPoolController`. | pooled line/protocol hook tests. |
| Pool `close()` bounded синхронно drain-ит workers; timeout/interruption/failure typed, а `closeAsync()` cancellation-isolated. | `PoolCloseSupport`, `WorkerPoolController`, pooled wrappers. | lifecycle unit tests, line/protocol integration tests, Java/Kotlin consumer compile. |
| Metrics являются согласованным snapshot состояний, durations, failures и retire reasons. | Pool metrics accumulator/controller snapshot. | pool metrics unit/integration tests. |

## Diagnostics и optional modules

| Инвариант | Владелец | Доказательство |
| --- | --- | --- |
| Diagnostics best-effort, bounded, schema-valid и не меняет outcome; bounded batch и FIFO-requeue не позволяют непрерывному destination занять dispatcher slots навсегда. | `DiagnosticEmitter`, `BoundedIsolatedTaskDispatcher`, `DiagnosticAttributeSchema`, scenario `DiagnosticsSettings`. | `DiagnosticEmitterTest`, diagnostic unit/integration tests. |
| Events одного lifecycle упорядочены для каждого recipient и связаны `runId`, включая переход между delivery batches. | Serial delivery внутри `DiagnosticEmitter`. | `DiagnosticEmitterTest`, diagnostics lifecycle tests. |
| Kotlin не меняет Java Draft semantics и не добавляет dependency в core. | `:procwright-kotlin`, Draft extension functions. | Kotlin public surface, persistence и dependency boundary tests. |
| Coroutine cancellation соблюдает ownership direct session, pooled worker, exit waiter и Flow collector. | Coroutine extensions и `StreamScenario.Draft.openFlow()`. | Kotlin cancellation/Flow tests. |
| Kotlin protocol factory создает отдельный adapter wrapper на factory call. | `protocolAdapterFactory`, `ProtocolAdapterFactoryDsl`. | Kotlin factory isolation tests. |
| Integrations используют core runtime и не добавляют внешнюю process library или MCP SDK. | `:procwright-integrations`, JPMS/build boundary. | module descriptor, external boundary и integration tests. |
| Protocol adapters отклоняют malformed JSON, invalid UTF-8, invalid headers и oversized frames до domain mapping. | `ProtocolAdapters`, `ContentLengthHeaders`, Jackson. | `ProtocolAdaptersTest` и external consumer examples. |
| Canonical Java/Kotlin/integration examples компилируются как внешние consumers в Gradle metadata и POM-only режимах. | Consumer fixture modules и publication metadata. | `publicApiConsumerCompilationCheck`, isolated publication smoke. |

## Release proofs

- `quickCheck` — unit, API/package boundaries и compilation всех public consumers.
- `scenarioCheck` — integration behavior канонических сценариев.
- `regressionCheck` — bounded stress и регрессии lifecycle/concurrency.
- `publicDocsCheck` и strict Java/Kotlin API docs gates.
- `publicationStructureCheck` проверяет classifiers и обязательные POM metadata всех трех modules.
- `publicationReadinessCheck` агрегирует product/API/docs readiness на Java 17 target.
- CI проверяет Java 17 artifact на Linux/macOS/Windows и JDK 17/21/25; source targets 21/25 — на Linux.
- Isolated publication consumers в Gradle metadata и Maven POM-only режимах.

Новый behavior должен расширить существующую строку или добавить новую. Если невозможно назвать единственного
владельца и proof, изменение не готово.
