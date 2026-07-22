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
- Protocol/session integration tests проверяют behavior через публичные сценарии, а не через internal classes.
