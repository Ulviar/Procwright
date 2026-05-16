# Архитектура MVP

## Цель

iCLI — JVM-библиотека для безопасного запуска внешних CLI-программ и управления интерактивными процессами из
Java/Kotlin приложений.

Первая версия должна доказать надежное process execution ядро. Она не должна сразу повторять всю ширину старого
проекта.

Главный архитектурный принцип описан в [invariant-architecture.md](invariant-architecture.md): широкие возможности
должны появляться через композицию маленьких валидированных объектов, а не через разрастание public API.

Внешний пользовательский слой при этом остается scenario-first. Пользователь выбирает workflow (`run`,
`lineSession`, `interactive`, `expect`), а библиотека разворачивает его в policies, общий launch plan и
scenario-specific execution plan. Это описано в [scenario-api.md](scenario-api.md).

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
- PTY как узкая transport strategy.
- Детерминированный fixture/eval набор.

## Не входит в MVP

- Process pooling.
- Stateful conversation affinity.
- MCP adapters.
- Kotlin coroutine/Flow модуль.
- Benchmarks.
- Competitor samples.
- Публичная diagnostics bus.
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
  Expect
  ExpectOptions
  ExpectOutputFilter
  ExpectException
  CapturePolicy
  ShutdownPolicy
  Session
  SessionOptions
  CommandException

com.github.ulviar.icli.spi
  ProcessLauncher
  PtyProvider
```

Публичных пакетов должно быть мало. Внутренняя реализация может быть разложена подробно, но это не должно
протекать в public API.

## Расширения после MVP

1. PTY hardening и кроссплатформенная матрица.
2. Более богатый expect DSL.
3. Listen-only streaming helper.
4. Kotlin extensions.
5. Process pooling отдельным модулем.
6. MCP adapter templates.
7. Benchmarks.
