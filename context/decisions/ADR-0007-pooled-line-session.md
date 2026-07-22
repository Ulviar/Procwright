# ADR-0007: Пулы session workers

## Статус

Принято.

## Контекст

Дорогой CLI worker полезно переиспользовать, но pool не должен становиться вторым process runtime или раскрывать
пользователю lease lifecycle. Повторное использование допустимо только для request/response протокола с доказуемыми
границами ответа, readiness и reset/health semantics.

## Решение

Pooling является вложенным вариантом уже выбранного сценария:

- `lineSession().pooled()` создает `PooledLineSession`;
- `protocolSession(adapterFactory).pooled()` создает `PooledProtocolSession<I, O>`;
- каждый protocol worker получает отдельный adapter из factory;
- pool открывает workers через те же line/protocol session paths и не создает собственный process engine;
- acquire/release/retire остаются внутренними, lease object не входит в public API.

`pooled()` фиксирует immutable worker Draft и возвращает unopened persistent `PoolDraft`. Pool workers появляются
только в `PoolDraft.open()`; порядок pool-level `with*` не меняет смысл других полей.

Pool policies задают `maxSize`, `warmupSize`, `minIdle`, acquire/hook/close timeouts, возраст и число запросов worker-а,
а также background replenishment. Оба pool-сценария поддерживают bounded reset и health hooks через соответствующий
session type. Readiness выбранного session scenario выполняется при каждом запуске worker, включая warmup и
replenishment.

## Инварианты

- live workers не превышают `maxSize`;
- request timeout, protocol failure, process exit и failed hooks retire worker;
- health hook выполняется до request; его failure или timeout не допускает lease;
- reset hook выполняется после успешного request; его failure или timeout retire-ит worker, но не заменяет уже полученный
  пользователем успешный response ошибкой;
- worker возвращается в idle только после успешного request и успешного reset hook;
- `closeAsync()` атомарно запрещает новые requests, начинает закрытие idle workers, позволяет уже leased workers
  завершить текущую операцию и возвращает cancellation-isolated future общего cleanup;
- `close()` запускает тот же cleanup и ожидает его не дольше `closeTimeout`; timeout дает `DRAIN_TIMEOUT`, но не отменяет
  продолжающийся cleanup;
- default `closeTimeout` равен 15 секундам: 5 секунд normal request + 2 секунды interrupt grace + 5 секунд kill grace +
  3 секунды запаса на scheduling и stream cleanup;
- interruption синхронного `close()` восстанавливает interrupt flag и дает `INTERRUPTED`; worker-close failure дает
  `WORKER_FAILED`, а `Error` сохраняет приоритет;
- metrics являются immutable snapshot и содержат состояния, latency и причины retirement;
- pool не меняет правило «один request одновременно на worker».

## Последствия

Startup cost можно амортизировать без нового runtime и без смешения close с return-to-pool. Raw session pooling,
stateful affinity и public leases не входят в этот контракт.
