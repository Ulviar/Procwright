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
- Optional Kotlin ergonomics module без Kotlin dependency в Java core.
- Pooled line-session scenario поверх существующих `LineSession` workers.
- Детерминированный fixture/eval набор.

## Не входит в MVP

- Raw session pooling.
- Stateful conversation affinity.
- MCP adapters.
- Benchmarks.
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

Transport
  pipe transport
  PTY transport

Testing/evals
  deterministic fixture
  behavior scenarios
```

## Предварительная пакетная форма

```text
com.github.ulviar.icli
  CommandService
  CommandSpec
  CommandResult
  RunOptions
  SessionInvocation
  LineSessionInvocation
  LineSession
  LineSessionOptions
  LineResponse
  ResponseDecoder
  StreamSession
  StreamOptions
  StreamInvocation
  StreamChunk
  StreamSource
  StreamListener
  StreamStdinPolicy
  StreamExit
  StreamTranscript
  StreamException
  PooledLineSession
  PooledLineSessionOptions
  PooledLineSessionInvocation
  PooledLineSessionMetrics
  PooledLineSessionException
  DiagnosticsOptions
  DiagnosticEvent
  DiagnosticEventType
  DiagnosticListener
  DiagnosticTranscriptSink
  CommandEcho
  Expect
  ExpectOptions
  ExpectOutputFilter
  ExpectException
  TerminalPolicy
  TerminalSize
  TerminalSignal
  PtyProvider
  PtyRequest
  CapturePolicy
  ShutdownPolicy
  Session
  SessionOptions
  CommandException

com.github.ulviar.icli.kotlin
  runCommand(...)
  runCommandAwait(...)
  openSession(...)
  awaitExit(...)
  requestAwait(...)
  ListenFlowInvocation
  listenFlow(...)
```

Публичных пакетов должно быть мало. Поэтому первый `PtyProvider` SPI находится в корневом пакете рядом с остальным
узким API, а не открывает отдельную иерархию `spi` до появления нескольких независимых ports. Внутренняя реализация
может быть разложена подробно, но это не должно протекать в public API.

## Расширения после MVP

1. PTY hardening и кроссплатформенная матрица.
2. Более богатый expect DSL.
3. Более богатый Kotlin DSL поверх optional Kotlin module.
4. Stateful affinity и raw session pooling поверх `pooled`.
5. MCP adapter templates.
6. Benchmarks.
