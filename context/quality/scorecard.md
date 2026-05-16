# Quality scorecard

## Статус

Фаза 2 начата. Ветка содержит контекст clean rewrite, Gradle foundation с Java 25 baseline, compile-tested API sketches,
детерминированную process fixture и первый one-shot execution kernel. `CommandService.run(...)` уже запускает реальные
процессы, но resolver/profile layer, interactive/session, expect и PTY еще не реализованы.

## Release-relevant критерии

| Область | Статус | Что должно быть верно |
| --- | --- | --- |
| Engineering charter | Active | Качество важнее скорости; требования закреплены как проектный стандарт. |
| Scenario API | Started | Compile-tested examples фиксируют `CommandService.run(...)` как one-shot workflow. |
| Invariant model | Started | Command/invocation/options разворачиваются в валидированный `ExecutionPlan`. |
| One-shot execution | Started | Direct argv, explicit shell, working directory, env, charset, timeout и drain покрыты integration tests. |
| Capture policy | Started | Bounded stdout/stderr capture и truncation flags покрыты; streaming/discard еще не добавлены. |
| Timeout/shutdown | Started | Timeout supervision и soft-then-hard shutdown path покрыты integration tests. |
| Command model | Started | Immutable command spec и per-call invocation builder компилируются и покрыты базовыми тестами. |
| Interactive session | Not started | Есть минимальная session abstraction с lifecycle tests. |
| Expect helper | Deferred | Добавляется только после session. |
| PTY | Deferred | Узкий transport/provider, без раздувания public API. |
| Fixture/evals | Started | Process fixture моделирует success, stderr, large output и timeout. |
| Documentation | Started | README описывает foundation и явно говорит, что runtime пока неполный. |
| Pooling | Deferred | Не входит в MVP. |

## Решения, которые нужно принять

- Итоговые имена `CommandSpec` / `CommandService` / `RunOptions`.
- Нужно ли оставлять `CommandService` итоговым именем или переименовать до stabilization.
- Полный набор capture policies после bounded-only MVP.
- Формат diagnostics/failure metadata до расширения streaming/session.

## Что считается прогрессом

- Новый public API компилируется в маленьких examples.
- Каждый новый behavior добавляет eval/test.
- Документы уменьшают неопределенность, а не заменяют реализацию.
