# Quality scorecard

## Статус

Фаза 6 реализуется. Ветка содержит контекст clean rewrite, Gradle foundation с Java 25 baseline, compile-tested API
sketches, детерминированную process fixture, one-shot execution kernel, scenario profile resolver для `run`, raw
interactive session scenario, line-oriented request/response workflow и expect automation helper.
`CommandService.run(...)`, `CommandService.interactive(...)` и `CommandService.lineSession(...)` уже запускают реальные
процессы через `ScenarioProfile -> LaunchPlan -> ExecutionPlan/SessionExecutionPlan`; `Session.expect(...)` добавляет
prompt automation поверх raw session. Streaming и PTY еще не реализованы.

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
| PTY | Deferred | Узкий transport/provider, без раздувания public API. |
| Fixture/evals | Started | Process fixture моделирует success, stderr, large output, timeout, session I/O и line workflow cases. |
| Documentation | Started | README описывает foundation, `run`, `interactive`, `lineSession`, `Expect` и явно говорит, что runtime пока неполный. |
| Pooling | Deferred | Не входит в MVP. |

## Решения, которые нужно принять

- Итоговые имена `CommandSpec` / `CommandService` / `RunOptions`.
- Нужно ли оставлять `CommandService` итоговым именем или переименовать до stabilization.
- Полный набор capture policies после bounded-only MVP.
- Формат diagnostics/failure metadata до расширения streaming/session.
- Когда переносить `TerminalPolicy` из модели профиля в реальный PTY transport.
- Нужно ли переименовать `SessionOptions.idleTimeout` перед публичной стабилизацией; текущая семантика зафиксирована
  как caller-visible activity: успешные записи, закрытие stdin и успешные чтения через session streams.

## Что считается прогрессом

- Новый public API компилируется в маленьких examples.
- Каждый новый behavior добавляет eval/test.
- Документы уменьшают неопределенность, а не заменяют реализацию.
