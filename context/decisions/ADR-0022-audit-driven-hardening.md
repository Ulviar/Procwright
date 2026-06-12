# ADR-0022: Доработки по итогам независимого аудита и отложенные API-решения

## Статус

Принят (2026-06-12).

## Контекст

Независимый аудит (runtime, public API, тесты, документация, build/CI/OSS-готовность) подтвердил соответствие проекта
собственному charter и нашел дефекты на стыках инвариантов плюс ряд предложений по API. Часть предложений
конфликтовала с ранее принятыми стабилизационными решениями (scorecard) — для них требуется явное решение, а не
молчаливое игнорирование.

## Решение: что исправлено

- Runtime: `closeStdin()` больше не может блокироваться на зависшей записи; off-by-one CRLF в лимитах строк;
  stderr protocol-сессии переведен на bounded drop-oldest и не убивает сессию (stdout остается строгим);
  посмертная зачистка потомков, удерживающих output pipe, через снапшот живых потомков; доставка diagnostics
  упорядочена per-recipient; follow-up запросы сообщают первопричину (`closed by an earlier failure`),
  смерть процесса при записи дает `PROCESS_EXITED`, а не `CLOSED`.
- API (аддитивно, baseline регенерирован до первой публикации): withers у `RunOptions`; `Duration.ZERO`
  единообразно означает «таймаут отключен»; `ExpectMatch` c capture groups; `CapturePolicy.toPath/discard`;
  `CommandInput.fromPath`; `CommandService.forCommand(CommandSpec)`; переименования
  `stdoutBacklogLines` (lines) / `outputBacklogLimit` (bytes) фиксируют единицы измерения в именах.
- Kotlin/integrations: `trySendBlocking` в `listenFlow`, suspend-обертки для protocol/pooled сессий,
  библиотечный `future.await()`, per-session executor в `JsonLineSession.requestAsync`,
  мост `JsonCodec.toJackson/fromJackson`.
- Verification: typed `DECODE_ERROR` доказан для run/lineSession; interrupt-статус и эскалация
  SIGTERM→KILL доказаны; `apiCompatibilityCheck` перенесен в `quickCheck` (как заявлял test-tiers);
  SPDX-заголовки enforced через spotless.

## Решение: что отложено сознательно

| Предложение аудита | Решение | Причина |
| --- | --- | --- |
| Распечатать sealed session handles или выпустить `procwright-testkit` с фейками | Отложено до запроса первых пользователей | Sealed-контракты — принятое стабилизационное решение (владение выводом); testkit — отдельный artifact с собственным lifecycle, его дизайн требует реальных потребительских сценариев. |
| Слить `protocolSession(adapter)` и `protocolSession(factory)` в один factory-вариант | Отложено до 0.2 | Breaking-слияние формы entry point; single-use вариант покрыт javadoc-подсказкой. Кандидат №1 при первом мажорном пересмотре API. |
| Общий интерфейс `ScenarioFailure` (reason/transcript) и переименование `CommandException` | Отложено до 0.2 | Меняет таксономию исключений целиком; текущая модель «один exception на сценарий» работает и протестирована. |
| JSpecify-аннотации в core | Отклонено для baseline | Конфликт с инвариантом «ядро без зависимостей вне JDK» (даже `requires static` расширяет module graph); пересмотреть при появлении пользовательского спроса. |
| `executeAsync(): CompletableFuture<CommandResult>` для `run` | Отклонено для baseline | Виртуальные потоки покрывают кейс; generic async API уже в списке отложенного. |
| Бинарные `StreamChunk` для `listen` | Отложено | Нет подтвержденного сценария; текстовая модель достаточна для заявленного «follow logs». |
| `TerminalSignal` расширение (EOF/SUSPEND) | Отложено | Один сигнал соответствует доказанному поведению; расширение требует PTY hardening. |
| Сокращение config-санитарии `CommandService` (defaults-объект) | Отложено | Чистая косметика поверх работающего API; не стоит breaking-изменения. |
| Jackson-зависимость integrations: zero-dep парсер или полный интероп | Частично решено | Добавлен мост `JsonNode` ↔ `JsonValue`; полный пересмотр зависимости — вместе с решением о реальном MCP adapter. |

## Последствия

Регенерация `config/api-compatibility/0.1.0/` до первой публикации легальна; после публикации 0.1.0 baseline
замораживается. Отложенные пункты не нужно поднимать заново без новых данных от пользователей — этот ADR
является источником истины об их статусе.
