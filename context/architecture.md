# Архитектура MVP

## Цель

iCLI — JVM-библиотека для безопасного запуска внешних CLI-программ и управления интерактивными процессами из
Java/Kotlin приложений.

Первая версия должна доказать надежное process execution ядро. Она не должна сразу повторять всю ширину старого
проекта.

Главный архитектурный принцип описан в [invariant-architecture.md](invariant-architecture.md): широкие возможности
должны появляться через композицию маленьких валидированных объектов, а не через разрастание public API.

Внешний пользовательский слой при этом остается scenario-first. Пользователь выбирает workflow (`run`,
`lineSession`, `interactive`, `expect`, `listen`, `pooled`), а библиотека разворачивает его в policies, общий launch
plan и scenario-specific execution plan. Это описано в [scenario-api.md](scenario-api.md).

## Входит в MVP

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
- Scenario presets как typed builder customizers без отдельного runtime.
- Optional CLI-backed integrations module без dependency на MCP SDK.
- Детерминированный fixture/eval набор.
- Bounded stress suite как часть `check`.
- Release hardening: license, cross-platform CI, versioning/compatibility policies, dependency review и release
  checklist.

## Не входит в MVP

- Raw session pooling.
- Stateful conversation affinity.
- Реальный MCP SDK adapter.
- Machine-dependent benchmarks/JMH.
- Competitor samples.
- Централизованная diagnostics bus или logging framework.
- Отдельный public runner под каждый сценарий.
- Release publishing.

## Слои

```text
Пользовательский API
  CommandService / CommandExecutor
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
com.github.ulviar.icli
  CommandService

com.github.ulviar.icli.command
  CommandSpec
  CommandResult
  RunOptions
  CommandInvocation
  CommandInput
  CapturePolicy
  OutputMode
  EnvironmentPolicy
  ShutdownPolicy
  CommandException
  CommandExecutionException

com.github.ulviar.icli.session
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
  LineResponse
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

com.github.ulviar.icli.diagnostics
  DiagnosticsOptions
  DiagnosticEvent
  DiagnosticEventType
  DiagnosticListener
  DiagnosticTranscriptSink
  CommandEcho

com.github.ulviar.icli.terminal
  TerminalPolicy
  TerminalSize
  TerminalSignal
  PtyProvider
  PtyRequest

com.github.ulviar.icli.preset
  ScenarioPresets

com.github.ulviar.icli.internal
  runtime, plans, validation and process helpers that must not appear in public signatures

com.github.ulviar.icli.internal.session
  stateful session-family implementations and runtime factories hidden by JPMS exports

com.github.ulviar.icli.kotlin
  runCommand(...)
  runCommandAwait(...)
  openSession(...)
  awaitExit(...)
  requestAwait(...)
  ListenFlowInvocation
  listenFlow(...)

com.github.ulviar.icli.integration
  JsonValue
  JsonCodec
  JsonLines
  JsonLineSession
  ContentLengthJsonFrames
  CancellableCall
  ToolCallResult
  CliAdapterError
  CommandBackedTool
```

Публичных core-пакетов должно быть мало, но они больше не должны превращать корень в плоский каталог всех типов.
`Session`, `Expect`, `LineSession`, `StreamSession` и `PooledLineSession` остаются в одном `session` package, потому
что разделяют инвариант единоличного владения stdout/stderr. Подробности зафиксированы в
[decisions/ADR-0014-package-architecture.md](decisions/ADR-0014-package-architecture.md).

## Расширения после MVP

1. PTY hardening и кроссплатформенная матрица.
2. Более богатый expect DSL.
3. Более богатый Kotlin DSL поверх optional Kotlin module.
4. Stateful affinity и raw session pooling поверх `pooled`.
5. Реальный MCP SDK adapter отдельным optional module поверх `:icli-integrations`.
6. Machine-dependent benchmarks/JMH после deterministic stress suite.
