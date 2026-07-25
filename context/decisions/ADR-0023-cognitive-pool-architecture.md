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
  зарезервированного `PoolWorker`; acquire/reservation transitions возвращают sealed variants, поэтому lease,
  reservation и interruption нельзя сочетать с неверным status или `null`; внутренними автоматами startup и physical
  retirement владеют отдельные компоненты;
- `PoolPartition` единолично представляет взаимоисключающие состояния `starting`, `idle`, `leased` и `retiring`;
  принадлежность коллекции является состоянием worker-а, но partition не покидает `WorkerPoolState`; bounded
  collections резервируют `maxSize` при создании, а переход добавляет worker в target до удаления из source;
- `WorkerStartup` выбирает ровно один terminal outcome между factory completion, timeout, close и interruption;
- `WorkerStartupCoordinator` владеет typed reservation, последовательностью worker-permit acquisition, launch, wait,
  abandon и failure mapping. `WorkerPoolState` атомарно создаёт и регистрирует reservation с заранее подготовленными
  lease и cleanup effects; reservation однократно передаёт ownership startup attempt, затем terminal transition либо
  выдаёт lease, либо инвалидирует его и запрещает повторный доступ к worker/effects; узкий state port выражает только
  startup events;
- `PoolWorker` владеет process-wide worker permit от допуска startup до удаления slot после полного retirement outcome:
  `session.close()`, terminal observation и physical output cleanup;
- `WorkerRetirement` создаётся вместе с reservation до регистрации slot, затем принимает factory session, атомарно
  заявляет начало close, выполняет потенциально реентрантный close action вне monitor и нормализует один возвращённый
  future в стабильный outcome;
- `WorkerRetirementCoordinator` владеет post-monitor batch: сначала инициирует все closes, затем обрабатывает outcomes и
  включает все outcomes в state и только после этого публикует late failures;
- `WorkerCloseSupport` владеет запуском exact-once physical close на отдельном owner, fallback в bounded retirement
  domain и объединением close, terminal и physical-output-cleanup outcomes;
- `PoolStateEffects` является одноразовым `AutoCloseable`: накапливает выбранные state-транзакцией retirement,
  worker-permit release и terminal-publication effects и при закрытии пытается выполнить их все вне monitor, даже если
  один effect завершился ошибкой;
- `PoolReplenisher` поддерживает не более одного активного цикла `minIdle` и владеет backoff;
- `WorkerPoolConstruction` владеет внешней транзакцией создания пула: warmup, запуск replenishment, commit и bounded
  rollback. При отказе он дожидается физического cleanup в пределах `closeTimeout`, сохраняет interruption и объединяет
  construction/cleanup failures без изменения исходных `Throwable`;
- `PoolTermination` сам владеет construction phase и pending reports и возвращает отдельные завершённые construction
  success/failure variants вместо tag, nullable payload и обязательного второго transition;
- `WorkerPoolPolicy` владеет immutable options, reuse policy и расчетом `minIdle`;
- `PooledRequestRunner` владеет observation, preparation и exact-once `WorkerPoolState.Lease`, не получая сырой
  `PoolWorker`;
- `PoolTermination` владеет решением construction, состоянием closing, приоритетом terminal failures и готовностью к
  drain; принятые failures хранятся как ordered identity-дедуплицированные данные. `PoolDrain` атомарно выдает
  единственный publication token, публикует зафиксированный outcome после освобождения pool monitor и сохраняет
  cancellation-isolated views;
- `PoolMetrics` владеет накопительными счетчиками и snapshot type, а текущие state counts получает от `PoolPartition`;
- `PoolFailurePublisher` владеет bounded late-failure publication;
- `PoolLifecycleDispatcher` предоставляет три независимых process-wide bounded domains: retirement outcome вместе с
  аварийным close fallback, reporting и replenishment; terminal publication в dispatcher не входит;
- controller координирует factory, hooks, physical close и reporting, но не содержит monitor и не дублирует mutable
  state. Сырой `PoolWorker` остаётся внутренней деталью state/startup/retirement collaborators и не достигает request
  runner или public pooled wrappers.

State transition выполняется под одним pool monitor. Factory, hooks, worker close, reporting и completion callbacks
выполняются вне monitor и возвращают typed outcome владельцу состояния. Worker permit отсоединяется от `PoolWorker` в
state-транзакции, но закрывается только через post-monitor effects.

Line и protocol public API, отсутствие public lease, timeout taxonomy, retirement reasons, metrics и worker reuse
сохраняются.

## Инварианты

- `starting + idle + leased + retiring == size <= maxSize`;
- worker принадлежит ровно одному состоянию, которое не дублируется mutable enum field;
- partition membership, worker-permit ownership, startup purpose/stage, retire reason и request count согласуются через
  `WorkerPoolState`; terminal startup и physical retirement имеют собственных владельцев;
- startup terminal winner выбирается ровно один раз, а поздний успешный startup обязательно retire-ится;
- каждый зарегистрированный worker уже имеет cleanup owner до запуска factory;
- retirement удерживает capacity до полного retirement outcome;
- lease завершается ровно один раз;
- одна acquire attempt атомарно получает idle lease или зарегистрированную startup reservation; attempt либо
  возвращает заранее подготовленный lease, либо сама завершает reservation; retries используют один исходный absolute
  deadline;
- close запрещает новые acquisitions, но не отнимает уже переданный lease;
- retirement batch не требует дополнительного queue permit;
- startup reservation до launch и startup attempt после launch не имеют совместного ownership;
- terminal startup reservation больше не раскрывает worker, prepared lease или effects; state transition принимает
  только effects, принадлежащие этой reservation;
- process-wide worker permits допускают суммарно не более 256 factory-admitted workers всех pools; reservation,
  ожидающая permit до factory, уже занимает slot своего pool, но ещё не входит в process-wide limit;
- pool не резервирует отдельный thread или process-wide slot для terminal future во время `open()`;
- closing во время construction является явным неуспешным результатом даже без attached cause;
- construction либо commit-ится один раз, либо проходит bounded rollback до возврата failure пользователю; controller
  не дублирует этот протокол;
- fatal background startup входит в terminal outcome до освобождения последнего startup slot; новая failure после drain
  claim публикуется ровно один раз как bounded late failure, а не меняет уже выбранный outcome;
- ни один внешний callback и ни одно завершение public future не выполняются под pool monitor;
- terminal failures фиксируются под pool monitor как ordered identity-дедуплицированные данные; стабильный aggregate
  создается без обхода cause/suppressed graph, вызова пользовательских accessors или изменения исходных `Throwable`, а
  его публикация выполняется после освобождения monitor;
- перед выходом через public pooled API aggregate с runtime primary разворачивается в свежую scenario-specific
  exception с сохраненными reason и message; aggregate с `Error` primary раскрывается напрямую, чтобы сохранить fatal
  type. Только свежая оболочка получает дополнительные diagnostics, пользовательские failures не изменяются;
- startup reservation создается до регистрации worker; terminal publication capability выбирается под pool monitor, а
  post-monitor effects выполняют её после освобождения monitor;
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
а `WorkerPoolControllerLifecycleTest` — construction rollback, независимое закрытие pools и восстановление worker
capacity.
Orchestration collaborators дополнительно доказываются через controller и pooled-wrapper contracts; line и protocol
integration tests проверяют пользовательские сценарии. `publicationReadinessCheck` включает unit, integration, bounded
stress, API/ABI, документацию, publication structure и consumer examples.
