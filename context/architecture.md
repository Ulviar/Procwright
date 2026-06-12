# Архитектура baseline 0.1.0

## Цель

Procwright — JVM-библиотека для безопасного запуска внешних CLI-программ и управления интерактивными процессами из
Java/Kotlin приложений.

Baseline `0.1.0` должен доказать надежное process execution ядро. Новая возможность добавляется только через понятный
сценарий, владельца инварианта и проверку.

Главный архитектурный принцип описан в [invariant-architecture.md](invariant-architecture.md): широкие возможности
должны появляться через композицию маленьких валидированных объектов, а не через разрастание public API.

Внешний пользовательский слой при этом остается scenario-first. Пользователь выбирает workflow (`run`,
`lineSession`, `protocolSession`, `interactive`, `expect`, `listen`, `lineSession().pooled()`,
`protocolSession(factory).pooled()`), а библиотека разворачивает его в policies, общий launch plan и
scenario-specific execution plan. Это описано в
[scenario-api.md](scenario-api.md).

## Входит в baseline 0.1.0

- One-shot запуск команды.
- Явная модель команды: executable, args, working directory, environment, shell mode.
- Безопасные дефолты: direct argv, bounded capture, explicit charset, timeout.
- Раздельный и объединенный stdout/stderr.
- Параллельный drain stdout/stderr.
- Soft shutdown перед hard kill.
- Минимальная interactive session abstraction.
- Минимальный line-oriented helper поверх session.
- Небольшой expect helper после стабилизации session.
- PTY как узкая transport strategy для session-сценариев.
- Listen-only streaming helper с bounded diagnostics.
- Наблюдательная diagnostics layer без влияния на runtime behavior.
- Correlation-safe diagnostics events с process-lifecycle `runId`.
- Optional Kotlin ergonomics module без Kotlin dependency в Java core.
- Pooled line-session scenario поверх существующих `LineSession` workers.
- Generic protocol-session scenario и typed protocol pool поверх существующих session/runtime границ.
- Scenario presets как typed builder customizers без отдельного runtime.
- Optional CLI-backed integrations module без dependency на MCP SDK.
- Детерминированный fixture/eval набор.
- Bounded stress suite как часть `check`.
- Release hardening: license, cross-platform CI, versioning/compatibility policies, dependency review и release
  checklist.
- Maven Central publishing/signing setup для Java 17-targeted artifacts без runtime impact.

## Не входит в baseline 0.1.0

- Raw session pooling.
- Stateful conversation affinity.
- Реальный MCP SDK adapter.
- Machine-dependent benchmarks/JMH.
- Competitor samples.
- Централизованная diagnostics bus или logging framework.
- Отдельный public runner под каждый сценарий.
- Automatic Maven Central publish без ручной проверки первого Central Portal deployment.

## Слои

```text
Пользовательский API
  Procwright / CommandService
  scenario methods
  CommandSpec
  CommandResult
  RunOptions
  Session
  Expect

Runtime
  launch plan
  resolved execution plan
  process launch
  stdout/stderr pumps
  session lifecycle state machine
  capture policies
  timeout supervision
  shutdown policy
  diagnostics emission
  duration saturation helpers

Transport
  pipe transport
  PTY transport

Testing/evals
  deterministic fixture
  behavior scenarios
```

## Пакетная форма ядра

```text
io.github.ulviar.procwright
  Procwright
  CommandService
  ProcwrightException
  RunScenario
  InteractiveScenario
  LineSessionScenario
  PooledLineSessionScenario
  ProtocolSessionScenario
  ReusableProtocolSessionScenario
  PooledProtocolSessionScenario
  StreamScenario

io.github.ulviar.procwright.command
  CommandSpec
  CommandResult
  RunOptions
  CommandInvocation
  CommandInput
  CharsetPolicy
  CapturePolicy
  OutputMode
  EnvironmentPolicy
  ShutdownPolicy
  CommandException
  CommandExecutionException

io.github.ulviar.procwright.session
  Session (interface)
  SessionInvocation
  SessionOptions
  SessionExit
  Expect (interface)
  ExpectOptions
  ExpectOutputFilter
  ExpectTranscriptValues
  ExpectException
  LineSessionInvocation
  LineSession (interface)
  LineSessionOptions
  LineSessionException
  LineResponse
  LineTranscript
  ResponseDecoder
  StreamSession (interface)
  StreamOptions
  StreamInvocation
  StreamChunk
  StreamSource
  StreamListener
  StreamStdinPolicy
  StreamExit
  StreamTranscript
  StreamException
  PooledLineSession (interface)
  PooledLineSessionOptions
  PooledLineSessionInvocation
  PooledLineSessionMetrics
  PooledLineSessionException
  ProtocolAdapter
  ProtocolReader
  ProtocolReaders
  ProtocolWriter
  ProtocolSession (interface)
  ProtocolSessionInvocation
  ProtocolSessionOptions
  ProtocolSessionException
  ProtocolTranscript
  PooledProtocolSession (interface)
  PooledProtocolSessionOptions
  PooledProtocolSessionInvocation
  PooledProtocolSessionMetrics
  PooledProtocolSessionException
  PooledWorkerRetireReason

io.github.ulviar.procwright.diagnostics
  DiagnosticsOptions
  DiagnosticEvent
  DiagnosticEventType
  DiagnosticListener
  DiagnosticTranscriptSink
  CommandEcho

io.github.ulviar.procwright.terminal
  TerminalPolicy
  TerminalSize
  TerminalSignal
  PtyProvider
  PtyRequest

io.github.ulviar.procwright.preset
  ScenarioPresets

io.github.ulviar.procwright.internal
  runtime, plans, validation and process helpers that must not appear in public signatures

io.github.ulviar.procwright.internal.session
  stateful session-family implementations and runtime factories hidden by JPMS exports

io.github.ulviar.procwright.kotlin
  runCommand(...) / runCommandAwait(...)
  openSession(...)
  awaitExit(...) для Session и StreamSession
  requestAwait(...)
  Kotlin duration overloads (timeout, idleTimeout, readinessTimeout,
    request, awaitDrained, acquireTimeout и другие DSL-параметры)
  pooledLineSession(...)
  protocolAdapter(...)
  listenFlow(...)
  ProcwrightDsl (annotation)
  ListenFlowInvocation
  PooledLineSessionDsl
  LineWorkerDsl
  ProtocolAdapterDsl

io.github.ulviar.procwright.integration
  JsonValue
  JsonCodec
  JsonNumbers
  JsonParseException
  JsonLines
  JsonLineSession
  ContentLengthJsonFrames
  ProtocolAdapters
  IntegrationProtocolException
  CancellableCall
  ToolCallResult
  CliAdapterError
  CommandBackedTool
```

Оркестрация внутри корневого пакета (`ScenarioRuntime`, `CommandServiceDefaults`, `ProtocolWorkerConfiguration`)
остается package-private и не входит в public API.

Публичных core-пакетов должно быть мало, но они больше не должны превращать корень в плоский каталог всех типов.
`Session`, `Expect`, `LineSession`, `ProtocolSession`, `StreamSession`, `PooledLineSession` и `PooledProtocolSession`
остаются в одном `session` package, потому что разделяют инвариант единоличного владения stdin/stdout/stderr и
session-family lifecycle. Подробности зафиксированы в
[decisions/ADR-0014-package-architecture.md](decisions/ADR-0014-package-architecture.md).

## Расширения вне baseline 0.1.0

1. PTY hardening и кроссплатформенная матрица.
2. Более богатый expect DSL.
3. Более богатый Kotlin DSL поверх optional Kotlin module.
4. Stateful affinity и raw session pooling поверх nested pooled session APIs.
5. Реальный MCP SDK adapter отдельным optional module поверх `:procwright-integrations`.
6. Machine-dependent benchmarks/JMH после deterministic stress suite.
