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
  зарезервированного `PoolWorker`; acquire transition возвращает sealed variants, поэтому lease, reservation и
  interruption нельзя сочетать с неверным status или `null`; warmup различает только successful reservation и closed
  pool, а replenishment использует семантику `tryReserve`; внутренними автоматами startup и physical retirement владеют
  отдельные компоненты;
- `PoolPartition` единолично представляет взаимоисключающие состояния `starting`, `idle`, `leased` и `retiring`;
  принадлежность коллекции является состоянием worker-а, но partition не покидает `WorkerPoolState`; bounded
  collections резервируют `maxSize` при создании, а переход добавляет worker в target до удаления из source;
- `WorkerStartup` выбирает ровно один terminal outcome между factory completion, timeout, close и interruption;
- `WorkerStartupCoordinator` владеет typed reservation, bounded launch, wait, abandon и failure mapping.
  `WorkerPoolState` атомарно создаёт и регистрирует reservation с подготовленным lease и
  worker cleanup owner. Startup purpose задаётся при создании worker и остаётся неизменным. Успешный launch claim под
  тем же monitor однократно передаёт ownership startup attempt; terminal transition затем либо выдаёт lease, либо
  инвалидирует внутренние capabilities. Reservation привязана к создавшему её `WorkerPoolState`, поэтому другой pool
  не может завершить её. Узкий state port выражает только startup events;
- `PoolWorker` хранит только lifecycle-local state одного worker: startup, принятую session, retirement, request count и
  retire reason;
- `WorkerRetirement` создаётся вместе с reservation до регистрации slot, затем принимает factory session, атомарно
  заявляет начало close, выполняет потенциально реентрантный close action вне monitor и нормализует один возвращённый
  future в стабильный outcome;
- `WorkerRetirementCoordinator` владеет post-monitor batch: сначала инициирует все closes, затем обрабатывает outcomes и
  включает все outcomes в state и только после этого публикует late failures;
- `WorkerCloseSupport` владеет exact-once запуском worker close, fallback в bounded retirement domain и ожиданием
  logical terminal outcome. Potentially blocking physical stream close наблюдается session runtime независимо и не
  удерживает pool capacity;
- `PoolStateEffects` является одноразовым `AutoCloseable`: накапливает выбранные state-транзакцией retirement,
  terminal-publication effects и при закрытии пытается выполнить их все вне monitor, даже если один effect завершился
  ошибкой;
- `PoolReplenisher` поддерживает не более одного активного цикла `minIdle` и владеет backoff;
- `WorkerPoolConstruction` владеет внешней транзакцией создания пула: warmup, запуск replenishment, commit и bounded
  rollback. При отказе он дожидается logical retirement в пределах `closeTimeout`, сохраняет interruption и не позволяет
  physical stream close переписать startup failure;
- `PoolTermination` сам владеет construction phase и pending reports и возвращает отдельные завершённые construction
  success/failure variants вместо tag, nullable payload и обязательного второго transition;
- `WorkerPoolSettings` является единственным configuration dialect и проходит полную validation до создания
  `WorkerPoolPolicy`; policy владеет reuse policy и чистым расчетом `minIdle` по state counts;
- `PooledRequestRunner` владеет observation, preparation и exact-once `WorkerPoolState.Lease`, не получая сырой
  `PoolWorker`;
- `PoolTermination` владеет решением construction, состоянием closing, приоритетом terminal failures и готовностью к
  drain. `PoolDrain` атомарно выдает единственный publication token, публикует зафиксированный outcome после освобождения
  pool monitor и сохраняет cancellation-isolated views;
- `PoolMetrics` владеет накопительными счетчиками и snapshot type, а текущие state counts получает от `PoolPartition`;
- `PoolFailurePublisher` владеет bounded late-failure publication;
- `PoolLifecycleDispatcher` ограничивает очереди retirement, reporting и replenishment как внутреннюю защиту runtime.
  Переполнение retirement queue выполняет обязательную работу на caller thread; эти пределы не задают пользовательскую
  квоту workers или процессов. Terminal publication в dispatcher не входит;
- controller координирует factory, hooks, worker close и reporting, но не содержит monitor и не дублирует mutable
  state. Каждая state-транзакция получает новый `PoolStateEffects` того же pool; metrics clock является внутренним
  total/non-throwing monotonic source. Сырой `PoolWorker` остаётся внутренней деталью state/startup/retirement
  collaborators и не достигает request runner или public pooled wrappers.

State transition выполняется под одним pool monitor. Factory, hooks, worker close, reporting и completion callbacks
выполняются вне monitor и возвращают typed outcome владельцу состояния.

Line и protocol pool handles остаются отдельными сценариями без public lease. Общий lifecycle выражают
`PooledSessionMetrics` и `PooledSessionException`, а request-level errors остаются line/protocol-specific. Timeout
taxonomy, retirement reasons и worker reuse сохраняются.

## Инварианты

- `starting + idle + leased + retiring == size <= maxSize`;
- worker принадлежит ровно одному состоянию, которое не дублируется mutable enum field;
- partition membership, startup purpose/stage, retire reason и request count согласуются через `WorkerPoolState`;
  startup purpose неизменяем и задаётся до регистрации worker; terminal startup и physical retirement имеют собственных
  владельцев;
- startup terminal winner выбирается ровно один раз, а поздний успешный startup обязательно retire-ится;
- каждый зарегистрированный worker уже имеет cleanup owner до запуска factory;
- retirement удерживает capacity до worker close и logical terminal outcome, но не до physical stream close;
- lease завершается ровно один раз;
- одна acquire attempt атомарно получает idle lease или зарегистрированную startup reservation; attempt либо
  возвращает заранее подготовленный lease, либо сама завершает reservation; retries используют один исходный absolute
  deadline;
- close запрещает новые acquisitions, но не отнимает уже переданный lease;
- retirement dispatch bounded; при заполненной очереди обязательная работа выполняется на caller thread и не теряется;
- startup reservation до launch и startup attempt после launch не имеют совместного ownership;
- успешный launch claim и передача ownership startup attempt являются одним переходом под pool monitor;
- startup completion принимает только уже переданный attempt; выдать lease непосредственно из reservation нельзя;
- terminal startup reservation не раскрывает worker или prepared lease; `PoolStateEffects` принадлежит pool, а не
  reservation, и создаётся отдельно для каждой state-транзакции;
- `maxSize` одного pool принимает значения от 1 до 256, по умолчанию равен 1 и ограничивает starting, idle, leased и
  retiring slots;
- независимые pools и direct sessions не делят process-global worker quota; суммарное число процессов задает приложение
  количеством ресурсов и `maxSize` каждого pool;
- startup и hooks имеют bounded admission; retirement processing использует fixed owner set и bounded queue с
  caller-runs backpressure при насыщении. Эти механизмы не являются пользовательской resource policy;
- closing во время construction является явным неуспешным результатом даже без attached cause;
- construction либо commit-ится один раз, либо проходит bounded rollback до возврата failure пользователю; controller
  не дублирует этот протокол;
- fatal background startup входит в terminal outcome до освобождения последнего startup slot; новая failure после drain
  claim публикуется ровно один раз как bounded late failure, а не меняет уже выбранный outcome;
- ни один внешний callback и ни одно завершение public future не выполняются под pool monitor;
- первый terminal outcome и дополнительный диагностический контекст фиксируются под pool monitor без обхода
  cause/suppressed graph; точная форма secondary failures не является контрактом, публикация выполняется после
  освобождения monitor;
- перед выходом через public pooled API ordinary terminal outcome получает scenario-specific exception с сохраненными
  reason и message; fatal outcome может распространяться как `Error`;
- startup reservation создается до регистрации worker; terminal publication capability выбирается под pool monitor, а
  отдельный post-monitor effects batch выполняет её после освобождения monitor;
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
revision wakeup; `PoolTerminationTest` проверяет construction/closing/drain как локальный инвариант.
`WorkerPoolControllerCapacityTest` проверяет construction и независимость pools,
`WorkerPoolControllerCloseIsolationTest` — public close и callbacks, а `WorkerPoolControllerRetirementTest` —
освобождение worker capacity и drain.
Orchestration collaborators дополнительно доказываются через controller и pooled-wrapper contracts; line и protocol
integration tests проверяют пользовательские сценарии. `publicationReadinessCheck` включает unit, integration, bounded
stress, API/ABI, документацию, publication structure и consumer examples.
