# ADR-0007: Pooled line-session scenario

## Статус

Accepted for Phase 11.

## Контекст

Прогретые CLI workers нельзя добавлять как отдельный process runtime: pool должен использовать уже проверенные session
primitives и не обходить
`ScenarioProfile -> ExecutionPlanResolver -> SessionRuntime -> LineSession`.

Первый полезный pooling-срез — line-oriented workers для REPL/daemon-like CLI, где дорогой старт процесса можно
амортизировать между несколькими request/response вызовами.

## Решение

Добавить сценарий `CommandService.pooled(...)`, который возвращает `PooledLineSession`.

Public surface:

- `PooledLineSessionInvocation` совмещает line-session launch overrides и pool policies;
- `PooledLineSessionOptions` задает `maxSize`, `warmupSize`, `acquireTimeout`, `maxRequestsPerWorker`,
  `maxWorkerAge`, `resetHook` и `healthCheck`;
- `PooledLineSession` предоставляет `request(...)`, `metrics()`, `close()`, `onDrained()` и `awaitDrained(...)`;
- `PooledLineSessionMetrics` возвращает snapshot counters;
- `PooledLineSessionException` различает pool-level failures (`ACQUIRE_TIMEOUT`, `CLOSED`, `WORKER_FAILED`).

Внутри pool открывает workers через тот же путь, что и `lineSession`: `LineSessionInvocation` резолвится в
`SessionExecutionPlan`, затем открывается `Session`, затем создается `LineSession`. Pool не создает собственный
`ProcessBuilder` path и не владеет transport logic.

`LineSession.close()` остается закрытием underlying процесса. Пользователь не получает lease object, чтобы не смешивать
return-to-pool и close. Возврат worker в pool происходит только внутри lifecycle одного `request(...)`.

## Инварианты

- pool не возвращает worker после timeout/failure user request;
- `maxRequestsPerWorker` и `maxWorkerAge` retire workers после завершения request;
- `healthCheck` выполняется перед lease; unhealthy worker закрывается и заменяется;
- `resetHook` выполняется после успешного request перед возвратом worker в idle set;
- `close()` запрещает новые requests, закрывает idle workers сразу и дает leased workers завершить текущий request;
- `metrics()` возвращает snapshot, а не live mutable state.

## Последствия

Плюсы:

- expensive REPL startup можно amortize без нового runtime;
- pooling остается scenario-first API, а не набором низкоуровневых lease primitives;
- lifecycle ошибки pool-level отделены от `LineSessionException`.

Минусы:

- первый pool intentionally line-oriented; raw session pooling и stateful affinity не включены;
- transcript остается worker-lifetime свойством `LineSession`, поэтому reset/health hooks должны учитывать stateful CLI;
- metrics пока локальные counters, без отдельной diagnostics event model.
