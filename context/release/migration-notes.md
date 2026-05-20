# Migration notes из старой iCLI

## Что переносится как идея

- Сервис вокруг базовой команды: пользователь создает `CommandService`, а затем выбирает сценарий.
- Fluent builders для per-call настройки.
- Typed results вместо неструктурированных строк и magic exit handling.
- Sessions как отдельный сценарий, а не как побочный режим `run`.
- Scenario-first API: пользователь выбирает workflow (`run`, `interactive`, `lineSession`, `expect`, `listen`,
  `pooled`), а не собирает низкоуровневый набор flags.
- Интеграционная пригодность: command-backed tools, JSON Lines и Content-Length framed protocols строятся поверх
  process harness, а не превращают core в agent framework.

## Что не переносится механически

- Старые task dossiers и большой контекст как ежедневная рабочая база.
- Разрастание public API вокруг частных вариантов запуска.
- Смешение process runtime, agent/tool registry и MCP SDK wrapper в одном core.
- Неявный shell mode.
- Silent fallback там, где сценарий требует terminal или другой capability.
- Heavy benchmarks до появления deterministic bounded stress suite.

## Соответствие новых сценариев старым ожиданиям

- One-shot command: `CommandService.run(...)`.
- Raw interactive process: `CommandService.interactive(...)`.
- Request/response REPL: `CommandService.lineSession(...)`.
- Prompt automation: `Session.expect(...)` или `Expect.on(session)`.
- Listen-only streaming: `CommandService.listen(...)`.
- Warm workers for repeated line requests: `CommandService.pooled(...)`.
- Common workflows: `ScenarioPresets` как typed builder customizers.
- Command-backed integration helpers: optional `:icli-integrations`.

## Миграция session-family handles после JPMS split

- `Session`, `Expect`, `LineSession`, `StreamSession` и `PooledLineSession` теперь являются публичными sealed handle
  interfaces, а не concrete implementation classes.
- Эти sealed interfaces не являются пользовательским SPI: пользователь получает handles через `CommandService` и не должен
  реализовывать или инстанцировать их самостоятельно.
- Stateful реализации скрыты в неэкспортируемом `com.github.ulviar.icli.internal.session`.
- Prompt automation строится через `Session.expect(...)` или `Expect.on(session)` только для sessions, созданных iCLI.
- Модуль `com.github.ulviar.icli` экспортирует только публичные packages: root, `command`, `diagnostics`, `preset`,
  `session`, `terminal`.

## Ограничения текущего baseline

- Публичный релиз еще не опубликован.
- Stateful affinity и raw session pooling остаются будущими расширениями.
- Реальный MCP SDK adapter должен быть отдельным optional module поверх `:icli-integrations`.
- Windows ConPTY provider пока не реализован; `TerminalPolicy.REQUIRED` должен давать explicit unsupported behavior,
  если provider недоступен.
