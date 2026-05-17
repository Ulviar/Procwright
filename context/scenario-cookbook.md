# Рецепты сценариев iCLI

## Назначение

Cookbook показывает, как выбирать сценарий iCLI по пользовательской задаче. Он не заменяет
[scenario-contracts.md](scenario-contracts.md): contracts фиксируют инварианты, а cookbook помогает выбрать правильный
workflow и не скатиться к ручной сборке process harness.

Каждый рецепт ниже привязан к compile-tested example method. Если рецепт меняется, соответствующий example должен
компилироваться в тестовых sources.

## Карта выбора

| Нужно пользователю | Сценарий | Compile-tested example |
| --- | --- | --- |
| Запустить команду и получить итоговый result | `run` | `oneShotScenario` |
| Задать command-level defaults один раз | `CommandSpec` + `CommandService` | `explicitCommandConfiguration` |
| Скомпоновать timeout, capture и shutdown policy | `run` policies | `policyComposition` |
| Управлять живым процессом через stdin/stdout | `interactive` | `interactiveScenario` |
| Автоматизировать prompt-oriented диалог | `interactive` + `Expect` | `expectScenario` |
| Делать request/response поверх line protocol | `lineSession` | `lineSessionScenario` |
| Требовать terminal capability | `interactive` + `TerminalPolicy.REQUIRED` | `terminalRequiredSessionScenario` |
| Читать поток вывода без накопления всего output | `listen` | `listenOnlyStreamingScenario` |
| Наблюдать lifecycle без раскрытия raw argv/env/output | `DiagnosticsOptions` | `diagnosticsScenario` |
| Переиспользовать line-oriented workers | `pooled` | `pooledLineSessionScenario` |
| Взять готовый workflow preset без нового runner | `ScenarioPresets` | `scenarioPresetComposition` |
| Обернуть CLI как tool adapter | `:icli-integrations` | `oneShotCommandBackedTool` |
| Общаться с JSON Lines worker | `JsonLineSession` | `jsonLineCommandBackedTool` |
| Отменить async JSON Lines call | `CancellableCall` | `cancellableJsonLineCall` |
| Читать/писать Content-Length JSON frames | `ContentLengthJsonFrames` | `contentLengthFramedJson` |

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

Compile-tested example:

- `expectScenario`

Инварианты:

- `Expect` работает поверх уже открытого `Session`;
- `Expect` claims stdout/stderr ownership;
- match buffer bounded;
- timeout и EOF различаются.

## `lineSession`

Используй `lineSession`, когда CLI работает как request/response protocol, где один request получает один logical
response. Это не raw terminal parser и не generic streaming API.

Compile-tested example:

- `lineSessionScenario`

Инварианты:

- requests сериализованы;
- decoder владеет правилом завершения response;
- timeout/failure закрывает session, чтобы не продолжать неизвестное protocol state;
- transcript bounded и доступен в ошибке.

## `listen`

Используй `listen`, когда нужно обрабатывать output chunks по мере поступления и не хранить весь output в памяти:
например log following или long-running watcher.

Compile-tested example:

- `listenOnlyStreamingScenario`

Инварианты:

- stdout/stderr дренируются параллельно;
- listener получает synchronous backpressure;
- diagnostic transcript bounded;
- listener failure завершает `StreamSession.onExit()` exceptionally и испускает diagnostics failure events.

## `pooled`

Используй `pooled`, когда один line-oriented worker дорогой в запуске, но protocol позволяет безопасно переиспользовать
worker между requests.

Compile-tested example:

- `pooledLineSessionScenario`

Инварианты:

- pool строится поверх `LineSession`, а не нового process runtime;
- `maxSize` ограничивает live workers;
- request timeout/failure retire worker;
- metrics являются snapshot, а не управляющим API.

## Диагностика

Используй `DiagnosticsOptions`, когда нужна наблюдаемость lifecycle без изменения поведения команды. Diagnostics не
является logging framework и не должен становиться самостоятельным workflow.

Compile-tested example:

- `diagnosticsScenario`

Инварианты:

- listener и transcript sink best-effort async;
- diagnostic failures не меняют command result;
- `CommandEcho` redaction-friendly;
- event attributes соответствуют [diagnostics.md](diagnostics.md).

## Сценарные presets

Используй `ScenarioPresets`, когда нужен готовый набор policy defaults для уже выбранного сценария. Preset не выбирает
сценарий вместо пользователя и не добавляет отдельный runner.

Compile-tested example:

- `scenarioPresetComposition`

Инварианты:

- preset является typed `Consumer` конкретного builder;
- preset не добавляет options, которых нет в сценарии;
- invalid preset parameters fail fast до запуска процесса.

## CLI-backed integrations

Используй `:icli-integrations`, когда внешний CLI нужно поднять на уровень command-backed tool, JSON Lines worker или
Content-Length framed JSON protocol. Этот модуль optional и не меняет core process runtime.

Compile-tested examples:

- `oneShotCommandBackedTool`
- `jsonLineCommandBackedTool`
- `cancellableJsonLineCall`
- `contentLengthFramedJson`

Инварианты:

- integration layer строится поверх существующих `run`/`lineSession` сценариев;
- CLI output считается untrusted data;
- adapter errors не включают raw stdout/stderr excerpts по умолчанию;
- cancellation сначала фиксирует outcome, затем закрывает underlying lifecycle owner.

## Релизный gate

Новый рецепт не считается готовым, пока:

- есть compile-tested example;
- рецепт сверяется с [scenario-contracts.md](scenario-contracts.md);
- если рецепт касается diagnostics, PTY, streaming, pooling или integrations, обновлены соответствующие eval/release
  gates;
- cookbook coverage test подтверждает связь документа с example methods.
