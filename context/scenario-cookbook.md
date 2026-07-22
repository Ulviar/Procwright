# Рецепты сценариев Procwright

## Назначение

Cookbook показывает, как выбирать сценарий Procwright по пользовательской задаче. Он не заменяет
[scenario-contracts.md](scenario-contracts.md): contracts фиксируют инварианты, а cookbook помогает выбрать правильный
workflow и не скатиться к ручной сборке process harness.

Каждый рецепт ниже привязан к compile-tested example method. Если рецепт меняется, соответствующий example должен
компилироваться в тестовых или external consumer sources.

## Карта выбора

| Нужно пользователю | Сценарий | Compile-tested example |
| --- | --- | --- |
| Запустить команду и получить итоговый result | `run` | `oneShotScenario` |
| Задать command-level defaults один раз | `CommandSpec` + `CommandService` | `explicitCommandConfiguration` |
| Скомпоновать timeout, capture и shutdown policy | `run` policies | `policyComposition` |
| Управлять живым процессом через stdin/stdout | `interactive` | `interactiveScenario` |
| Автоматизировать prompt-oriented диалог | `interactive` + `Expect` | `expectScenario` |
| Делать request/response поверх line protocol | `lineSession` | `lineSessionScenario` |
| Делать request/response поверх framed/typed protocol | `protocolSession` | `protocolSessionScenario` |
| Требовать terminal capability | `interactive` + `TerminalPolicy.REQUIRED` | `terminalRequiredSessionScenario` |
| Читать поток вывода без накопления всего output | `listen` | `listenOnlyStreamingScenario` |
| Ждать readiness по output долгоживущего процесса | `listen` + application readiness check | `daemonReadinessScenario` |
| Наблюдать lifecycle без раскрытия raw argv/env/output | scenario `withDiagnostic*` | `diagnosticsScenario` |
| Переиспользовать line-oriented workers | `lineSession().pooled()` | `pooledLineSessionScenario` |
| Переиспользовать typed protocol workers | `protocolSession(factory).pooled()` | `pooledProtocolSessionScenario` |
| Общаться с JSON Lines worker | `protocolSession(ProtocolAdapters.jsonLinesSession(...))` | [`JsonLineIntegrationExample`](../docs/examples/integrations/io/github/ulviar/procwright/examples/integration/JsonLineIntegrationExample.java) |
| Общаться с Content-Length JSON worker | `protocolSession(ProtocolAdapters.contentLengthJsonSession(...))` | [`TypedContentLengthJsonSessionExample`](../docs/examples/integrations/io/github/ulviar/procwright/examples/integration/TypedContentLengthJsonSessionExample.java) |

## `run`

Используй `run`, когда нужен завершенный результат процесса: exit code, stdout/stderr snapshots, duration и diagnostic
metadata. Пользователь выбирает сценарий `run`, а не собирает threads, pumps и timeout watchers вручную.

Compile-tested examples:

- `oneShotScenario`
- `explicitCommandConfiguration`
- `policyComposition`

Инварианты:

- direct argv является default;
- shell mode включается явно;
- capture bounded по умолчанию;
- timeout проходит через shutdown policy;
- raw argv/env values не должны попадать в diagnostic echo или launch failure message.

## `interactive`

Используй `interactive`, когда caller должен напрямую управлять живым процессом: писать в stdin, читать raw output streams
или передать output ownership helper-у вроде `Expect`.

Compile-tested examples:

- `interactiveScenario`
- `terminalRequiredSessionScenario`

Инварианты:

- `Session` владеет process lifecycle;
- output ownership выбирается один раз: raw streams или higher-level helper;
- idle timeout закрывает зависшую session через общий shutdown path;
- terminal capability доступна только в session-family сценариях.

## `Expect`

Используй `Expect`, когда процесс ведет prompt-oriented диалог и caller хочет ждать literal/regex output, отправлять
строки и получать bounded transcript при timeout/EOF.

Compile-tested examples:

- `expectScenario`

Инварианты:

- `Expect` работает поверх уже открытого `Session`;
- `Expect` claims stdout/stderr ownership;
- match buffer bounded;
- timeout и EOF различаются.

## `lineSession`

Используй `lineSession`, когда CLI работает как request/response protocol, где один request получает один logical
response. Это не raw terminal parser и не generic streaming API.

Compile-tested examples:

- `lineSessionScenario`
- `strictBoundedLineSessionScenario`

Инварианты:

- requests сериализованы;
- decoder владеет правилом завершения response;
- validation, size, encoding и wait failure допускают retry, только если request не передан writer-у и гарантированно не
  сможет записаться позже; после передачи writer-у failure и другие protocol failures закрывают session, чтобы не
  продолжать неизвестное protocol state;
- transcript bounded и доступен в ошибке.

## `protocolSession`

Используй `protocolSession`, когда worker protocol является request/response, но ответ не сводится к одной stdout line:
multi-line documents, byte frames, delimiter/content-length framing, JSON frames или typed request/response adapter.

Compile-tested example:

- `protocolSessionScenario`

Инварианты:

- adapter владеет framing и response decoder;
- runtime владеет process lifecycle, timeout, output pumps, transcript и cleanup;
- один request одновременно;
- request/response size limits fail как typed protocol errors;
- strict charset decoding не заменяет битые bytes молча;
- readiness probe выполняется до возврата session.

## `listen`

Используй `listen`, когда нужно обрабатывать output chunks по мере поступления и не хранить весь output в памяти:
например log following или long-running watcher.

Compile-tested example:

- `listenOnlyStreamingScenario`
- `daemonReadinessScenario`

Инварианты:

- stdout/stderr дренируются параллельно;
- listener получает synchronous backpressure;
- diagnostic transcript bounded;
- listener failure завершает `StreamSession.onExit()` exceptionally и испускает diagnostics failure events.

## Nested pooled scenarios

Используй `lineSession().pooled()`, когда один line-oriented worker дорогой в запуске, но protocol позволяет безопасно
переиспользовать worker между requests. Используй `protocolSession(factory).pooled()`, когда то же требуется для typed
protocol workers с per-worker adapter state.

Compile-tested examples:

- `pooledLineSessionScenario`
- `drainedPooledLineSessionScenario`
- `pooledProtocolSessionScenario`

Инварианты:

- pool строится поверх `LineSession` или `ProtocolSession`, а не нового process runtime;
- `maxSize` ограничивает live workers;
- `warmupSize` и `minIdle` управляют readiness дорогих workers;
- timeout до получения worker никого не retire; timeout/failure после получения worker retire этот worker;
- metrics являются snapshot, а не управляющим API.

## Диагностика

Используй `withDiagnosticListener(...)` и `withDiagnosticTranscriptSink(...)` выбранного Draft, когда нужна
наблюдаемость lifecycle без изменения поведения команды. Diagnostics не является logging framework и не должен
становиться самостоятельным workflow.

Compile-tested example:

- `diagnosticsScenario`

Инварианты:

- listener и transcript sink best-effort async;
- diagnostic failures не меняют command result;
- `CommandEcho` redaction-friendly;
- event attributes соответствуют [diagnostics.md](diagnostics.md).

## Protocol integrations

Используй `:procwright-integrations`, когда внешний CLI говорит по JSON Lines, delimiter-framed или Content-Length JSON
протоколу. Модуль поставляет только adapters для `protocolSession` и не меняет core process runtime.

Compile-tested programs:

- [`JsonLineIntegrationExample`](../docs/examples/integrations/io/github/ulviar/procwright/examples/integration/JsonLineIntegrationExample.java)
- [`TypedContentLengthJsonSessionExample`](../docs/examples/integrations/io/github/ulviar/procwright/examples/integration/TypedContentLengthJsonSessionExample.java)

Инварианты:

- integration layer строится поверх `protocolSession`;
- framing adapter владеет wire format, а `typedJsonSession` — domain mapping; lifecycle, deadlines и bounds остаются у
  `ProtocolSession`;
- CLI output считается untrusted data;

## Релизный gate

Новый рецепт не считается готовым, пока:

- есть compile-tested example;
- рецепт сверяется с [scenario-contracts.md](scenario-contracts.md);
- если рецепт касается diagnostics, PTY, streaming, pooling или integrations, обновлены соответствующие eval/release
  gates;
- пример компилируется и выполняется как внешний consumer.
