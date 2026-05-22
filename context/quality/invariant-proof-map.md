# Карта доказательства инвариантов

## Назначение

Этот документ связывает ключевые инварианты iCLI с владельцами в коде и проверками. Test/eval доказывает инвариант, но
не владеет им. Владелец должен быть value object, policy, resolver, state machine, validator или runtime component.

## Core invariants

| Инвариант | Владелец | Proof |
| --- | --- | --- |
| Пользователь выбирает сценарий, а не runtime flags. | `CommandService`, `ScenarioProfile`, scenario invocation builders. | `CommandServiceApiExamples`, scenario docs, `context/scenario-contracts.md`. |
| Runtime получает validated execution/session plan. | `ExecutionPlanResolver`, `LaunchPlan`, `ExecutionPlan`, `SessionExecutionPlan`, `StreamExecutionPlan`. | `ExecutionPlanResolverTest`, integration tests for `run`, `interactive`, `lineSession`, `listen`. |
| Direct argv является безопасным default, shell mode явный. | `CommandSpec`, `CommandValidation`, `SystemShell`. | `CommandSpecTest`, `OneShotExecutionIntegrationTest`, security docs. |
| Environment inheritance является явной policy с compatibility default. | `EnvironmentPolicy`, command/session/stream invocation builders, resolver. | `CommandInvocationTest`, `SessionInvocationTest`, `StreamInvocationTest`, `OneShotExecutionIntegrationTest`, security docs. |
| One-shot output bounded by policy and reports truncation. | `CapturePolicy`, `CapturedOutput`, `ProcessKernel`, `CommandResult`. | `CapturedOutputTest`, `CommandResultTest`, `OneShotExecutionIntegrationTest`, `ProcessStressTest`. |
| Timeout завершает process tree через shutdown policy. | `RunOptions`, `ShutdownPolicy`, `ProcessLifecycle`, `ProcessKernel`, session runtime. | `OneShotExecutionIntegrationTest`, `ProcessStressTest`, `TestCliStressTest`. |
| Stdin ownership is explicit and safe. | `CommandInput`, `StdinPolicy`, command/session/stream runtimes. | `OneShotExecutionIntegrationTest`, `InteractiveSessionIntegrationTest`, `StreamScenarioIntegrationTest`. |
| Session output ownership cannot be shared by incompatible readers. | `SessionOutputOwnership`, `DefaultSession`, scenario wrappers. | `SessionOutputOwnershipTest`, `ExpectIntegrationTest`, `LineSessionIntegrationTest`, streaming tests. |
| Raw session lifecycle is idempotent and observable. | `DefaultSession`, `SessionExit`, `SessionOptions`. | `InteractiveSessionIntegrationTest`, Kotlin await tests, diagnostics tests. |
| Line requests are serialized and cannot interleave responses. | `DefaultLineSession`, `ResponseDecoder`, line request lock/state. | `LineSessionIntegrationTest`, `PooledLineSessionIntegrationTest`, `ProcessStressTest`. |
| Protocol requests are serialized and adapter-owned without exposing raw leases. | `DefaultProtocolSession`, `ProtocolAdapter`, `ProtocolReader`, `ProtocolWriter`, protocol request lock/state. | `ProtocolSessionIntegrationTest`, `CommandServiceApiExamples.protocolSessionScenario`, protocol-session docs. |
| Readiness probes run after launch and before session or worker availability. | `ReadinessSupport`, `CommandService`, `SessionInvocation`, `LineSessionInvocation`, `ProtocolSessionInvocation`. | `LineSessionIntegrationTest`, `ProtocolSessionIntegrationTest`, scenario contracts. |
| Strict charset decoding can report malformed output as typed failure. | `CharsetPolicy`, `ProcessKernel`, `DefaultLineSession`, `DefaultProtocolSession`, transcript buffers. | `CharsetPolicyTest`, `LineSessionIntegrationTest`, `ProtocolSessionIntegrationTest`, results/errors docs. |
| Protocol request/response size limits are separate from transcript retention. | `ProtocolSessionOptions`, `DefaultProtocolSession.Writer`, `DefaultProtocolSession.Reader`, `ResponseBudget`. | `PolicyValueTest`, `ProtocolSessionIntegrationTest`. |
| Protocol failure taxonomy is stable and domain-mappable. | `ProtocolSessionException`, `PooledProtocolSessionException`, `CliAdapterError`. | `ProtocolSessionIntegrationTest`, `CliAdapterErrorTest`, results/errors docs. |
| Line transcripts and stream diagnostics are bounded. | `BoundedTranscriptBuffer`, `LineTranscript`, `StreamTranscript`, `ExpectOptions`, `LineSessionOptions`, `StreamOptions`. | `BoundedTranscriptBufferTest`, line/expect/stream integration tests. |
| Expect action values are redacted by default in transcripts and failure messages. | `ExpectOptions`, `ExpectTranscriptValues`, `DefaultExpect`. | `ExpectIntegrationTest`, security docs. |
| Terminal-required scenarios never silently fall back to pipes. | `TerminalPolicy`, `PtyProvider`, `ProcessTransport`. | `TerminalCapabilityBoundaryTest`, `PtyTransportIntegrationTest`, platform docs. |
| Streaming does not retain full output and propagates listener failures. | `StreamOptions`, `StreamInvocation`, `DefaultStreamSession`, `StreamRuntime`. | `StreamScenarioIntegrationTest`, diagnostics tests, stress tests. |
| Diagnostics do not change execution behavior. | `DiagnosticsOptions`, `DiagnosticEmitter`, async listener/sink delivery. | `DiagnosticsIntegrationTest`, `DiagnosticsOptionsTest`, diagnostics docs. |
| Public API surface does not grow accidentally and does not leak internal or external process-library types. | JPMS descriptors, exact public API baseline tests, external boundary check. | `PublicApiSurfaceTest`, `PublicIntegrationApiSurfaceTest`, `PublicKotlinApiSurfaceTest`, `PackageBoundaryTest`, `ExternalLibraryBoundaryTest`, `externalLibraryBoundaryCheck`. |
| Pooled line sessions reuse existing line-session runtime rather than creating a second process runtime. | `PooledLineSessionInvocationDefaults`, `DefaultPooledLineSession`, `SessionScenarioSupport`. | `PooledLineSessionInvocationDefaultsTest`, `PooledLineSessionIntegrationTest`, stress tests. |
| Pooled protocol sessions reuse existing protocol-session runtime and keep adapter state worker-owned. | `protocolSession(factory).pooled()`, serialized per-worker adapter factory, `PooledProtocolSessionInvocation`, `DefaultPooledProtocolSession`, `SessionScenarioSupport`. | `ProtocolSessionIntegrationTest`, `CommandServiceApiExamples.pooledProtocolSessionScenario`. |
| Pool hooks are bounded and pool metrics separate acquire wait from request duration. | `PooledLineSessionOptions`, `PooledProtocolSessionOptions`, `DefaultPooledLineSession`, `DefaultPooledProtocolSession`, `WorkerHookSupport`. | `PooledLineSessionIntegrationTest`, `ProtocolSessionIntegrationTest`, `PolicyValueTest`. |
| Pool metrics snapshots cannot represent impossible state. | `PooledLineSessionMetrics`, `PooledProtocolSessionMetrics`. | `PolicyValueTest`, pool integration tests. |
| Scenario presets do not create runners or bypass builders/resolver. | `ScenarioPresets`. | `ScenarioPresetsTest`, scenario presets docs, ADR-0008. |
| Kotlin ergonomics do not add Kotlin dependency to Java core. | Gradle module boundaries, `:icli-kotlin`. | `PublicKotlinApiSurfaceTest`, `externalLibraryBoundaryCheck`, dependency review. |
| CLI integrations do not pull MCP SDK or external process libraries into core. | `:icli-integrations`, module descriptor, dependency review. | `IntegrationModuleDescriptorTest`, `CommandBackedToolTest`, `ExternalLibraryBoundaryTest`. |
| JSON/framing helpers reject malformed or oversized protocol data. | `JsonCodec`, `JsonLines`, `ContentLengthJsonFrames`, `ProtocolAdapters`, `IntegrationProtocolException`. | `JsonCodecTest`, `ContentLengthJsonFramesTest`, `JsonLineSessionTest`, `ProtocolAdaptersTest`. |
| Test CLI can model unstable real-world process behavior. | `:icli-test-cli` scenario registry and scenario implementations. | `:icli-test-cli:check`, `TestCliStressTest`, `context/evals/test-cli-simulator.md`. |

## Release proof gates

- Fast contracts: `./gradlew quickCheck`.
- Scenario behavior: `./gradlew scenarioCheck`.
- Regression and bounded stress: `./gradlew regressionCheck`.
- Public docs and Java API docs: `./gradlew publicDocsCheck`.
- Complete local gate: `./gradlew releaseCandidateCheck`.

## Maintenance rule

Любой новый public API или behavior должен добавлять строку в эту карту либо явно расширять существующий инвариант.
Если владелец инварианта неочевиден, изменение не готово к merge.
