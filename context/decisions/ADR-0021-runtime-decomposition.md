# ADR-0021: Декомпозиция scenario runtime и protocol session

## Статус

Accepted.

## Контекст

Сценарный API должен оставаться компактным: пользователь выбирает `run`, `interactive`, `lineSession`,
`protocolSession`, `listen` или pool-вариант, а не собирает runtime из низкоуровневых частей. При этом внутренний код не
должен концентрировать разные инварианты в одном большом классе, если границу ответственности можно описать ясно.

## Решение

`CommandService` остается публичным facade-объектом вокруг команды, defaults и сценарных entry points.
Оркестрация сценариев вынесена во внутренний `ScenarioRuntime`: он строит invocation objects, резолвит execution/session
plans, открывает runtime wrappers, применяет readiness checks и передает diagnostics дальше в runtime.

`DefaultProtocolSession` остается владельцем lifecycle протокольной сессии, serialized request lock, transcript snapshot
и process exit snapshot. Внутренние детали чтения и записи разделены на маленькие владельцы:

- `ProtocolRequestWriter` владеет stdin writes, request deadline и request byte/char limits.
- `ProtocolOutputQueue` владеет bounded очередью между output pump и protocol reader.
- `ProtocolResponseReader` владеет deadline-aware чтением, framing helpers, text decoding и per-read limits.
- `ProtocolResponseBudget` владеет global response byte/char limits на один request.
- `ProtocolRuntimeFailures` является внутренней границей создания failures с transcript/process snapshot владельца
  сессии.

Публичное API не получает новых runtime-конструкторов или SPI из-за этой декомпозиции. Единственное публичное расширение
в этом решении — общий `IcliException` как catch boundary для ошибок, произведенных iCLI; сценарные exception-типы
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

- `io.github.ulviar.icli` как root package становится разрешенным dependency для subpackages из-за публичного
  `IcliException`.
- Protocol internals остаются package-private и не являются SPI; расширяемость для пользователей по-прежнему идет через
  `ProtocolAdapter`.

## Проверка

- `PublicApiSurfaceTest` фиксирует появление `IcliException` как осознанное public API изменение.
- `PackageBoundaryTest` допускает dependency на root package только как public error boundary.
- `IcliExceptionTest` и `IntegrationExceptionTest` проверяют общий exception contract.
- Protocol/session integration tests проверяют behavior через публичные сценарии, а не через internal classes.
