# ADR-0023: Когнитивная архитектура worker pool

## Статус

Принято.

## Контекст

Pool runtime одновременно отвечает за capacity, startup, leases, retirement, replenishment, terminal outcome и
metrics. Эти обязанности нельзя сводить в один controller, но механическое разбиение на helpers также вредно: оно
заменяет локальные инварианты скрытыми протоколами между классами.

Архитектура должна позволять понять один компонент без удержания в голове всего pool. Внутренние типы оправданы только
тогда, когда каждый из них владеет самостоятельным инвариантом.

## Решение

### Consistency domain

`WorkerPoolState` является единственным consistency domain pool. Один monitor сериализует:

- взаимоисключающее состояние каждого зарегистрированного worker;
- capacity и выдачу lease;
- накопительные metrics;
- construction, closing и terminal outcome;
- request count, retire reason и решения о replenishment.

Одна state-транзакция сначала изменяет состояние и выбирает обязательную post-commit работу. После освобождения monitor
она запускает physical retirement и публикует terminal outcome. Пользовательские callbacks, factory, hooks, close action
и completion continuations под monitor не выполняются.

`PoolPartition` хранит один identity map `worker -> state`. Состояния `STARTING`, `IDLE`, `LEASED` и `RETIRING`
взаимоисключающие. Отдельная FIFO-очередь является только индексом idle workers, а не вторым источником состояния.

`PoolMetrics` хранит накопительные события, а текущие counts получает только из `PoolPartition`. Поздно созданный
worker, отделённый от logical pool при close, не возвращается в текущие counts. После его physical retirement
накопительные `created` и `retired` обновляются одной state-транзакцией.

### Worker lifecycle

`PoolWorker` является startup slot, а после успешного factory result — владельцем принятой session, retirement,
request count, startup duration и retire reason. Отдельной reservation и заранее подготовленного lease нет.

`WorkerStartup` владеет одним terminal race между factory completion, timeout, interruption и close. Он также владеет
factory thread и одним outcome future с тремя вариантами: созданный worker, исходная ошибка factory или причина остановки.
Coordinator отображает этот результат в public failure без промежуточных исключений и повторного чтения winner.
Поздняя отмена не меняет выбранный результат; interruption вызывающего потока восстанавливает его flag. Проигравший
поздний factory result передаётся ровно один раз в late-completion callback. Число одновременных startups ограничивает
`maxSize` конкретного pool; независимые pools не делят process-global startup admission.

`WorkerStartupCoordinator` не содержит второго автомата. Он последовательно выполняет state preflight membership,
close и deadline через узкий port, запуск, ожидание и отображение результата в typed pool failures.

`WorkerPoolState.Lease` создаётся только в атомарном переходе `STARTING -> LEASED`. Lease не раскрывается public API и
может быть освобождён или retire-нут ровно один раз.

После успешного factory result `PoolWorker` одной операцией принимает session и создаёт `WorkerRetirement`.
`WorkerRetirement` поэтому не имеет состояния «ещё нет session» и владеет только exact-once запуском close action и
стабильным outcome. `WorkerRetirementCoordinator` сначала инициирует весь выбранный batch, затем наблюдает outcomes вне
pool monitor и возвращает их в state.

### Closing

`PoolTermination` владеет construction phase, первым terminal failure, очередью construction diagnostics, единственным
drain claim и cancellation-isolated future views. Publication token выбирается под monitor и исполняется после него.

При close:

- новые acquire и startup запрещаются;
- все `STARTING` немедленно отделяются от partition и перестают быть logical drain gate;
- ожидающий startup caller наблюдает terminal decision `CLOSED`;
- поздний successful worker закрывается напрямую и никогда не становится idle или leased;
- `IDLE` переходит в `RETIRING`;
- уже выданный lease остаётся у caller и retire-ится при возврате;
- physical close отделённого late worker не задерживает terminal future; после retirement он отражается только в
  накопительных metrics.

`WorkerPoolConstruction` владеет warmup, commit и bounded rollback. Один `PoolReplenisher` на pool поддерживает
`minIdle` цепочкой одношаговых попыток с backoff. Между попытками он хранит не более одной scheduled task в общем
fixed-parallelism `PoolReplenishmentScheduler`; размер его очереди зависит от числа live pools, а не от частоты failures.
`PoolReplenisher` хранит pending/running attempt и cancellation handle под одним monitor; отдельного автомата
запланированной задачи нет. Позднее получение handle после close отменяет задачу, после начала выполнения — не
прерывает её. Fatal failure запрещает новые attempts до вызова внешнего failure handler. Закрытие
pool удаляет pending task из scheduler. Backoff не занимает worker thread, поэтому failing pool не удерживает lifecycle
owner бесконечным retry-loop. `PooledRequestRunner` владеет request observation и exact-once возвратом lease.

`PoolLifecycleDispatcher` failure-atomically создаёт фиксированный набор daemon owner-потоков в стандартном
`ThreadPoolExecutor` и использует bounded internal queue. Owner-потоки не завершаются по idle timeout, поэтому после
успешного construction обычная отправка task не зависит от повторного вызова thread factory. Saturation policy
различается по смыслу работы: обязательный retirement выполняется вызывающим потоком сверх executor parallelism, а
producer публикации diagnostics ждёт места в отдельной очереди. Рекурсивная отправка из owner thread выполняется сразу
и не может заблокироваться на собственной очереди.

## Инварианты

- `starting + idle + leased + retiring == size <= maxSize` для logical partition;
- один identity map является единственным источником registered worker state;
- startup purpose неизменяем и задаётся до регистрации worker;
- factory не запускается без успешного state claim;
- terminal startup winner выбирается один раз;
- timeout или interruption после запуска удерживает slot до late completion, если pool остаётся открыт;
- close отделяет любой `STARTING` независимо от состояния factory thread;
- late worker закрывается и не возвращается в logical partition;
- текущие metrics имеют единственный источник в partition; late physical retirement атомарно обновляет только
  накопительные counters;
- worker принимает session и startup duration одной операцией;
- lease создаётся только после принятия session и перехода в `LEASED`;
- lease завершается ровно один раз;
- обычный retirement удерживает capacity до logical close outcome;
- terminal outcome публикуется один раз после освобождения monitor;
- первый terminal failure стабилен; дополнительные failures публикуются отдельно без контракта на точный
  `cause`/`suppressed` graph;
- controller не содержит monitor и не получает partition;
- production lifecycle не содержит переходов, существующих только ради тестов.

## Отклонённые варианты

- Actor на каждый pool добавляет mailbox, owner-thread lifecycle и future hops в синхронный API.
- Несколько synchronization domains требуют lock ordering и межкомпонентных handshake.
- Отдельные reservation ownership, startup stage и prepared lease дублируют уже существующие partition, terminal race и
  факт запуска factory.
- Отдельные drain и effects owners не владеют самостоятельным инвариантом: их обязанности локальны для state transition
  и `PoolTermination`.
- Попытка гарантировать атомарный rollback при `OutOfMemoryError` добавляет сложность, но не даёт надёжной гарантии JVM
  после исчерпания памяти.

## Проверка

`PoolPartitionTest`, `PoolMetricsTest`, `PoolTerminationTest`, `WorkerPoolStateTest`, `WorkerStartupTest`,
`WorkerStartupCoordinatorTest`, `WorkerRetirementTest` и `WorkerRetirementCoordinatorTest` проверяют локальных
владельцев. Controller lifecycle tests проверяют races, construction, capacity, close, retirement и replenishment.
Line/protocol integration и stress tests доказывают пользовательские сценарии без public lease.
