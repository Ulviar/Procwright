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

- `WorkerPoolState` является aggregate root единственного consistency domain пула. Он владеет monitor,
  `PoolPartition`, `PoolMetrics`, `PoolTermination`, revision ожиданий и связанными с partition полями уже
  зарезервированного `PoolWorker`; внутренними автоматами startup и physical retirement владеют отдельные компоненты;
- `PoolPartition` единолично представляет взаимоисключающие состояния `starting`, `idle`, `leased` и `retiring`;
  принадлежность коллекции является состоянием worker-а, но partition не покидает `WorkerPoolState`; bounded
  collections резервируют `maxSize` при создании, а переход добавляет worker в target до удаления из source;
- `WorkerStartup` выбирает ровно один terminal outcome между factory completion, timeout, close и interruption;
- `WorkerStartupCoordinator` владеет typed reservation, последовательностью admission, launch, wait, abandon и failure
  mapping. `WorkerPoolState` атомарно создаёт и регистрирует reservation с заранее подготовленными lease и cleanup
  effects; reservation однократно передаёт ownership startup attempt, затем terminal transition либо выдаёт lease,
  либо инвалидирует его и запрещает повторный доступ к worker/effects; узкий state port выражает только startup events;
- `WorkerRetirement` создаётся вместе с reservation до регистрации slot, затем принимает admission и factory session
  без аллокации, инициирует close ровно один раз и нормализует любой close outcome;
- `WorkerRetirementCoordinator` владеет post-monitor batch: сначала инициирует все closes, затем наблюдает outcomes и
  включает все outcomes в state и только после этого публикует late failures;
- `PoolStateEffects` является одноразовым `AutoCloseable`: накапливает выбранные state-транзакцией retirement,
  admission-release и terminal-publication effects и при закрытии пытается выполнить их все вне monitor, даже если
  один effect завершился ошибкой;
- `PoolReplenisher` поддерживает не более одного активного цикла `minIdle` и владеет backoff;
- `WorkerPoolPolicy` владеет immutable options, reuse policy и расчетом `minIdle`;
- `PooledRequestRunner` владеет observation, preparation и exact-once `WorkerPoolState.Lease`, не получая сырой
  `PoolWorker`;
- `PoolTermination` владеет решением construction, состоянием closing, приоритетом terminal failures и claim/publish
  единственного drain outcome; publication token и terminal action создаются до claim, а вложенный `PoolDrain`
  сохраняет cancellation-isolated views;
- `PoolTerminalPublisher` до запуска worker/adapter factory резервирует один из 256 process-wide terminal slots,
  предоставляет pool отдельного disposable non-inheriting owner-а и освобождает slot только после возврата synchronous
  continuations terminal future; construction guard отправляет owner-у abort action, если controller не был создан;
- `PoolMetrics` владеет накопительными счетчиками и snapshot type, а текущие state counts получает от `PoolPartition`;
- `PoolFailurePublisher` владеет bounded late-failure publication;
- `PoolLifecycleDispatcher` предоставляет независимые process-wide bounded domains для retirement, reporting и
  replenishment; terminal publication в него не входит;
- controller координирует factory, hooks, physical close и reporting, но не содержит monitor и не дублирует mutable
  state. Сырой `PoolWorker` остаётся внутренней деталью state/startup/retirement collaborators и не достигает request
  runner или public pooled wrappers.

State transition выполняется под одним pool monitor. Factory, hooks, worker close, reporting и completion callbacks
выполняются вне monitor и возвращают typed outcome владельцу состояния. Retirement admission отсоединяется от worker-а
в state-транзакции, но закрывается только через post-monitor effects.

Line и protocol public API, отсутствие public lease, timeout taxonomy, retirement reasons, metrics и worker reuse
сохраняются.

## Инварианты

- `starting + idle + leased + retiring == size <= maxSize`;
- worker принадлежит ровно одному состоянию, которое не дублируется mutable enum field;
- partition membership, admission ownership, startup purpose/stage, retire reason и request count согласуются через
  `WorkerPoolState`; terminal startup и physical retirement имеют собственных владельцев;
- startup terminal winner выбирается ровно один раз, а поздний успешный startup обязательно retire-ится;
- каждый зарегистрированный worker уже имеет cleanup owner до запуска factory;
- retirement удерживает capacity до полного physical cleanup;
- lease завершается ровно один раз;
- одна acquire attempt атомарно получает idle lease или зарегистрированную startup reservation; attempt либо
  возвращает заранее подготовленный lease, либо сама завершает reservation; retries используют один исходный absolute
  deadline;
- close запрещает новые acquisitions, но не отнимает уже переданный lease;
- retirement batch не требует дополнительного worker admission;
- startup reservation до launch и startup attempt после launch не имеют совместного ownership;
- terminal startup reservation больше не раскрывает worker, prepared lease или effects; state transition принимает
  только effects, принадлежащие этой reservation;
- process-wide worker admission и accepted pool terminal lifecycles имеют независимые limits по 256;
- terminal slot резервируется во время `open()` до worker/adapter factory; отсутствие slot дает typed `STARTUP_FAILED`;
- accepted pool не получает terminal admission во время `closeAsync()`; его disposable owner публикует outcome после
  освобождения worker slots и admissions;
- closing во время construction является явным неуспешным результатом даже без attached cause;
- fatal background startup входит в terminal outcome до освобождения последнего startup slot; новая failure после drain
  claim публикуется ровно один раз как bounded late failure, а не меняет уже выбранный outcome;
- блокирующая synchronous continuation удерживает только terminal slot своего pool: она не задерживает closes других
  accepted pools, но при занятых 256 slots не позволяет открыть новый pool;
- ни один внешний callback и ни одно завершение public future не выполняются под pool monitor;
- startup reservation и terminal publication owner создаются до регистрации worker или claim drain; post-monitor
  effects выбираются под pool monitor и выполняются после его освобождения;
- controller не содержит `synchronized` и не получает ссылку на monitor или partition;
- тестовые seams находятся на границах владельцев и не добавляют test-only переходы в production lifecycle.

## Отклоненные варианты

- Actor на каждый pool добавляет mailbox, owner-thread lifecycle и future hops в синхронный API.
- Несколько независимо синхронизированных state owners переносят сложность в lock ordering и handshake protocols.
- Механическое разбиение исходного controller по файлам не уменьшает число одновременно удерживаемых инвариантов.
- Предварительное резервирование capacity каждого внутреннего effects-списка усложняет state transitions ради попытки
  пережить `OutOfMemoryError` во время служебной аллокации. JVM после исчерпания памяти не даёт надежной транзакционной
  гарантии всему process runtime, поэтому pool сохраняет обязательный порядок effects и обработку ошибок callbacks,
  но не строит отдельный протокол OOME-atomicity.

## Проверка

State owners проверяются прямыми unit tests. `WorkerPoolStateTest` проверяет составной lifecycle, close-транзакцию и
revision wakeup; `PoolTerminationTest` проверяет construction/closing/drain как локальный инвариант,
`PoolTerminalPublisherTest` — изоляцию owners и удержание terminal slot synchronous continuation, а
`WorkerPoolControllerLifecycleTest` — отказ до factory и восстановление capacity.
Orchestration collaborators дополнительно доказываются через controller и pooled-wrapper contracts; line и protocol
integration tests проверяют пользовательские сценарии. `publicationReadinessCheck` включает unit, integration, bounded
stress, API/ABI, документацию, publication structure и consumer examples.
