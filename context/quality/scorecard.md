# Quality scorecard

## Статус

Фаза 1 начата. Ветка содержит контекст clean rewrite, Gradle foundation с Java 25 baseline, compile-tested API sketches и
детерминированную process fixture. Реального execution kernel еще нет: `CommandService.run(...)` валидирует invocation
callback и явно падает как не реализованный до фазы 2.

## Release-relevant критерии

| Область | Статус | Что должно быть верно |
| --- | --- | --- |
| Engineering charter | Active | Качество важнее скорости; требования закреплены как проектный стандарт. |
| Scenario API | Started | Compile-tested examples фиксируют `CommandService.run(...)` как one-shot workflow. |
| Invariant model | Started | `CommandSpec`, `CapturePolicy`, `ShutdownPolicy` и fixture result уже валидируют базовые инварианты. |
| One-shot execution | Not started | Direct argv запуск, параллельный drain stdout/stderr. |
| Capture policy | Started | Bounded capture имеет value object и тесты; streaming/discard еще не добавлены. |
| Timeout/shutdown | Started | Shutdown policy value object есть; runtime shutdown behavior начнется в фазе 2. |
| Command model | Started | Immutable command spec и per-call invocation builder компилируются и покрыты базовыми тестами. |
| Interactive session | Not started | Есть минимальная session abstraction с lifecycle tests. |
| Expect helper | Deferred | Добавляется только после session. |
| PTY | Deferred | Узкий transport/provider, без раздувания public API. |
| Fixture/evals | Started | Process fixture моделирует success, stderr, large output и timeout. |
| Documentation | Started | README описывает foundation и явно говорит, что runtime пока неполный. |
| Pooling | Deferred | Не входит в MVP. |

## Решения, которые нужно принять

- Итоговые имена `CommandSpec` / `CommandService` / `RunOptions`.
- Полный набор one-shot policies для фазы 2.
- Формат structured failure model для реального runtime.

## Что считается прогрессом

- Новый public API компилируется в маленьких examples.
- Каждый новый behavior добавляет eval/test.
- Документы уменьшают неопределенность, а не заменяют реализацию.
