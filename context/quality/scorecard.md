# Quality scorecard

## Статус

Текущая ветка содержит только контекст для чистого переписывания. Кода библиотеки пока нет.

## Release-relevant критерии

| Область | Статус | Что должно быть верно |
| --- | --- | --- |
| Invariant model | Not started | Value objects, policies и resolver изолируют правила API/runtime. |
| One-shot execution | Not started | Direct argv запуск, параллельный drain stdout/stderr. |
| Capture policy | Not started | Bounded, streaming и discard policies имеют тесты. |
| Timeout/shutdown | Not started | Есть soft-then-hard shutdown и понятная диагностика. |
| Command model | Not started | Immutable command spec и per-call builder удобны из Java/Kotlin. |
| Interactive session | Not started | Есть минимальная session abstraction с lifecycle tests. |
| Expect helper | Deferred | Добавляется только после session. |
| PTY | Deferred | Узкий transport/provider, без раздувания public API. |
| Fixture/evals | Not started | Детерминированные сценарии покрывают основные failure modes. |
| Documentation | Not started | README описывает только реализованное поведение. |
| Pooling | Deferred | Не входит в MVP. |

## Решения, которые нужно принять

- Java baseline: Java 21 или Java 25.
- PTY packaging: core dependency или optional module.
- Итоговые имена `CommandSpec` / `CommandService` / `RunOptions`.
- Формат fixture: test utility или маленький CLI-модуль.

## Что считается прогрессом

- Новый public API компилируется в маленьких examples.
- Каждый новый behavior добавляет eval/test.
- Документы уменьшают неопределенность, а не заменяют реализацию.
