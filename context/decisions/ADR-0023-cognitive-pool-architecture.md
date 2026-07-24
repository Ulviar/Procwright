# ADR-0023: Когнитивная архитектура worker pool

## Статус

Принято.

## Контекст

Pool runtime содержит несколько независимых инвариантов: partition workers, startup races, request lease, retirement,
replenishment, terminal publication и metrics. Если ими одновременно владеет controller, локальное изменение требует
помнить несколько автоматов и synchronization domains.

Уменьшение числа строк само по себе проблему не решает. Механический перенос методов в helper-классы оставил бы те же
скрытые протоколы между ними.

## Решение

Pool runtime перестраивается вокруг следующих владельцев:

- `PoolPartition` единолично представляет взаимоисключающие состояния `starting`, `idle`, `leased` и `retiring`;
  принадлежность коллекции является состоянием worker-а;
- `WorkerStartup` выбирает ровно один terminal outcome между factory completion, timeout, close и interruption;
- `WorkerStartupCoordinator` владеет typed reservation, последовательностью admission, launch, wait, abandon и failure
  mapping, а `StartupPoolState` оставляет под pool monitor только атомарные переходы partition;
- `WorkerRetirement` инициирует close ровно один раз и нормализует любой close outcome;
- `WorkerRetirementCoordinator` владеет post-monitor batch: сначала инициирует все closes, затем наблюдает outcomes и
  публикует late failures;
- `PoolReplenisher` поддерживает не более одного активного цикла `minIdle` и владеет backoff;
- `WorkerPoolPolicy` владеет immutable options, reuse policy и расчетом `minIdle`;
- `PooledRequestRunner` владеет observation, preparation и request lease и гарантирует один release или retirement;
- `PoolDrain` владеет единственным terminal outcome и cancellation-isolated views;
- `PoolTerminalPublisher` до запуска worker/adapter factory резервирует один из 256 process-wide terminal slots,
  предоставляет pool отдельного disposable non-inheriting owner-а и освобождает slot только после возврата synchronous
  continuations terminal future;
- `PoolMetrics` владеет накопительными счетчиками и snapshot type, а текущие state counts получает от `PoolPartition`;
- `PoolFailurePublisher` владеет bounded late-failure publication;
- `PoolLifecycleDispatcher` предоставляет независимые process-wide bounded domains для retirement, reporting и
  replenishment; terminal publication в него не входит;
- controller координирует владельцев, но не дублирует их состояние.

State transition выполняется под одним pool monitor. Factory, hooks, worker close, reporting и completion callbacks
выполняются вне monitor и возвращают typed outcome владельцу состояния.

Line и protocol public API, отсутствие public lease, timeout taxonomy, retirement reasons, metrics и worker reuse
сохраняются.

## Инварианты

- `starting + idle + leased + retiring == size <= maxSize`;
- worker принадлежит ровно одному состоянию, которое не дублируется mutable enum field;
- startup terminal winner выбирается ровно один раз, а поздний успешный startup обязательно retire-ится;
- retirement удерживает capacity до полного physical cleanup;
- lease завершается ровно один раз;
- close запрещает новые acquisitions, но не отнимает уже переданный lease;
- retirement batch не требует дополнительного worker admission;
- startup reservation до launch и startup attempt после launch не имеют совместного ownership;
- process-wide worker admission и accepted pool terminal lifecycles имеют независимые limits по 256;
- terminal slot резервируется во время `open()` до worker/adapter factory; отсутствие slot дает typed `STARTUP_FAILED`;
- accepted pool не получает terminal admission во время `closeAsync()`; его disposable owner публикует outcome после
  освобождения worker slots и admissions;
- блокирующая synchronous continuation удерживает только terminal slot своего pool: она не задерживает closes других
  accepted pools, но при занятых 256 slots не позволяет открыть новый pool;
- ни один внешний callback и ни одно завершение public future не выполняются под pool monitor;
- тестовые seams находятся на границах владельцев и не добавляют test-only переходы в production lifecycle.

## Отклоненные варианты

- Actor на каждый pool добавляет mailbox, owner-thread lifecycle и future hops в синхронный API.
- Несколько независимо синхронизированных state owners переносят сложность в lock ordering и handshake protocols.
- Механическое разбиение исходного controller по файлам не уменьшает число одновременно удерживаемых инвариантов.

## Проверка

State owners проверяются прямыми unit tests. `PoolTerminalPublisherTest` проверяет изоляцию owners и удержание terminal
slot synchronous continuation, а `WorkerPoolControllerLifecycleTest` — отказ до factory и восстановление capacity.
Orchestration collaborators дополнительно доказываются через controller и pooled-wrapper contracts; line и protocol
integration tests проверяют пользовательские сценарии. `publicationReadinessCheck` включает unit, integration, bounded
stress, API/ABI, документацию, publication structure и consumer examples.
