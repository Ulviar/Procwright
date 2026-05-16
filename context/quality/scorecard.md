# Quality scorecard

## Статус

Фаза 10 реализована в текущем срезе. Ветка содержит контекст clean rewrite, Gradle foundation с Java 25 baseline,
compile-tested API sketches, детерминированную process fixture, one-shot execution kernel, scenario profile resolver для
`run`, raw interactive session scenario, line-oriented request/response workflow, expect automation helper, первый PTY
transport, listen-only streaming scenario, первый diagnostics/observability слой и optional Kotlin ergonomics module.
`CommandService.run(...)`, `CommandService.interactive(...)` и `CommandService.lineSession(...)` уже запускают реальные
процессы через `ScenarioProfile -> LaunchPlan -> ExecutionPlan/SessionExecutionPlan`; `CommandService.listen(...)`
добавляет streaming через `StreamExecutionPlan`, а `Session.expect(...)` добавляет prompt automation поверх raw session.
Session-сценарии поддерживают `TerminalPolicy.DISABLED`, `AUTO` и `REQUIRED`; PTY доступен через узкий `PtyProvider`
SPI и системный Unix-провайдер на базе `script(1)`. Diagnostics events подключены к service-owned сценариям:
`run`, `interactive`, `lineSession` и `listen`. Kotlin module дает extension/suspend/Flow API без Kotlin dependency в
Java core.

## Release-relevant критерии

| Область | Статус | Что должно быть верно |
| --- | --- | --- |
| Engineering charter | Active | Качество важнее скорости; требования закреплены как проектный стандарт. |
| Scenario API | Started | `run` выбирает сценарный профиль, а не напрямую набор runtime flags. |
| Invariant model | Started | `ScenarioProfile + CommandSpec + CommandInvocation/SessionInvocation` разворачиваются в валидированные scenario-specific plans. |
| One-shot execution | Started | Direct argv, explicit shell, stdin, working directory, env, charset, timeout и drain покрыты integration tests. |
| Capture policy | Started | Bounded stdout/stderr capture и truncation flags покрыты; streaming/discard еще не добавлены. |
| Timeout/shutdown | Started | Timeout supervision покрыт integration tests; forceful shutdown branch реализован, но требует отдельной hardening-проверки. |
| Command model | Started | Immutable command spec и per-call invocation builder компилируются и покрыты базовыми тестами. |
| Interactive session | Started | Raw `Session` имеет защищенный stdin, raw stdout/stderr, `onExit`, idempotent close и caller-visible idle timeout tests. |
| Line session | Started | `LineSession` сериализует request/response, поддерживает custom decoder, bounded transcript, различение EOF/timeout и stderr drain. |
| Expect helper | Started | Literal/regex matching, send/sendLine, bounded transcript, различение timeout/EOF и ANSI filter покрыты tests. |
| PTY | Started | `TerminalPolicy`, `PtyProvider`, Unix `script(1)` provider, explicit unsupported behavior, terminal size request и Ctrl+C-style signal mapping покрыты tests. |
| Streaming/listen | Started | `listen` закрывает stdin по умолчанию, дренирует stdout/stderr, dispatches chunks, хранит bounded diagnostics, timeout/listener failure покрыты tests. |
| Diagnostics | Started | Structured lifecycle/timeout/truncation events, redaction-friendly command echo без raw argv/env values, async listener SPI, transcript sink и diagnostic test recorder покрыты tests для service scenarios. |
| Kotlin ergonomics | Started | Optional `:icli-kotlin` module компилируется отдельно, содержит receiver extensions, suspend wrappers, узкий `ListenFlowInvocation` без listener override и Flow adapter tests без silent drops. |
| Fixture/evals | Started | Process fixture моделирует success, stderr, large output, timeout, session I/O, line workflow и streaming cases. |
| Documentation | Started | README описывает foundation, `run`, `interactive`, `lineSession`, `Expect`, PTY, `listen`, diagnostics и Kotlin module, явно говорит, что runtime пока неполный. |
| Pooling | Deferred | Не входит в MVP. |

## Решения, которые нужно принять

- Итоговые имена `CommandSpec` / `CommandService` / `RunOptions`.
- Нужно ли оставлять `CommandService` итоговым именем или переименовать до stabilization.
- Полный набор capture policies после bounded-only MVP.
- Нужно ли добавлять отдельные `Expect`-level diagnostic events до public stabilization; process lifecycle уже живет
  на уровне owning `Session`.
- Нужно ли добавлять ordered/bounded dispatcher для diagnostics transcript sink, если best-effort событий станет мало.
- Когда Kotlin compiler начнет поддерживать следующие JVM targets, сверять `:icli-kotlin` target с Java baseline.
- Нужен ли отдельный optional PTY artifact после расширения platform matrix.
- Какой Windows ConPTY provider будет добавлен: отдельный artifact или runtime-specific implementation.
- Нужно ли переименовать `SessionOptions.idleTimeout` перед публичной стабилизацией; текущая семантика зафиксирована
  как caller-visible activity: успешные записи, закрытие stdin и успешные чтения через session streams.

## Что считается прогрессом

- Новый public API компилируется в маленьких examples.
- Каждый новый behavior добавляет eval/test.
- Документы уменьшают неопределенность, а не заменяют реализацию.
