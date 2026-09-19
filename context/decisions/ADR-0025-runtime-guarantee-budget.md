# ADR-0025: Бюджет гарантий runtime

## Статус

Принято и реализовано.

Этот ADR задаёт действующую границу гарантий. Её изменение требует согласованного изменения кода, тестов,
public docs и proof map; текущий код и его black-box tests остаются источником истины.

## Контекст

Procwright ценен тем, что простой сценарный API скрывает корректную работу с timeout, output, памятью и жизненным циклом
процесса. Но защита от патологических реализаций `Process`, `Charset`, callback и служебных потоков породила собственную
платформу исполнения: глобальные admission lanes, заранее зарезервированные publication owners, сложную атрибуцию
physical close failures и точную агрегацию поздних ошибок.

Эти механизмы увеличивают число состояний и гонок самого runtime. Гарантия оправдана только тогда, когда защищает
реалистичный пользовательский отказ и её реализация проще создаваемого ею риска. Размер кода является индикатором, но не
самостоятельной целью.

## Контрольные пользовательские задачи

Упрощение обязано сохранять три сквозных класса задач:

1. Конечная команда вроде конвертации файла: аргументы без shell injection, input/output policy, bounded capture,
   timeout, exit result и cleanup дерева процессов.
2. Тысячи независимых запросов к долгоживущему line или protocol worker: сериализация одного worker, bounded acquire и
   request deadline, readiness, retirement недостоверного worker и ограниченный pool.
3. Общение с локальной моделью:
   - отдельный framed request/response выполняется через `protocolSession`;
   - двунаправленный инкрементальный диалог выполняется через raw `interactive`, где пользователь владеет прикладным
     framing поверх stdin/stdout;
   - `listen` остаётся output-only сценарием с bounded streaming и backpressure.

Новый typed bidirectional streaming helper не является целью: эти три маршрута уже покрывают разные модели владения, а
их объединение создало бы ещё один сложный lifecycle.

Compile-tested consumer examples и black-box integration/stress tests этих задач важнее тестов внутренней формы
runtime.

## Обязательные гарантии

Procwright сохраняет:

- scenario-first grammar `command -> scenario -> immutable with* Draft -> execute/open`;
- прямой argv launch без неявного shell, defensive configuration snapshots и отсутствие launch до terminal call;
- параллельный drain stdout/stderr, exclusive output ownership и bounded capture/transcript/backlog;
- request/response byte, char и line limits там, где они имеют отдельный пользовательский смысл;
- strict и replacement charset decoding как явную policy;
- absolute timeout для библиотечной операции, восстановление interrupt status и идемпотентный close;
- best-effort graceful-to-forceful cleanup root и descendants, обнаруженных bounded scans во время cleanup;
- один стабильный пользовательский terminal outcome;
- сериализацию line/protocol requests и retirement worker после неоднозначного I/O;
- request-scoped protocol writer/readers и fresh adapter на каждый session или worker;
- readiness до возврата session или допуска worker в pool;
- bounded pool size и acquire wait, отсутствие public lease и закрытие активных/idle workers;
- typed scenario failures, exit metadata, диагностически полезные pool metrics и стабильные причины retirement;
- bounded streaming с последовательной listener delivery;
- bounded Expect transcript/match window и изоляцию потенциально катастрофического regex;
- Kotlin и protocol integrations как тонкие optional layers без второго process runtime.

Границы line/protocol output buffers следуют response limits согласно
[ADR-0027](ADR-0027-response-sized-output-buffers.md). Отдельные настройки транспортной очереди не являются гарантией;
общий reason `RESPONSE_TOO_LARGE` не зависит от того, заметила превышение очередь или request reader.
Trusted PTY boundary и chunk-based complete-field decoding уточнены в
[ADR-0028](ADR-0028-trusted-extensions-and-bounded-decoding.md).

## Гарантии, которые больше не являются целью

Runtime не обязан гарантировать:

- корректную работу с `Process`, `ProcessHandle`, `CharsetEncoder` или `CharsetDecoder`, нарушающими JDK contract;
- per-call timeout или thread isolation custom `PtyProvider` и возвращённых им process objects;
- точное число consumed bytes после terminal character-limit failure `readTextExactly`;
- отдельный fresh non-inheriting thread и независимую process-global admission partition для каждого вида callback;
- fairness или FIFO между внутренними cleanup, reporting и callback tasks разных handles;
- восстановление служебного callback owner после его внутренней поломки;
- доставку ошибки callback, возникшей после abandonment, через JVM uncaught-exception handler;
- публичный контракт на точную форму secondary cause/suppressed graph, identity-дедупликацию и продвижение позднего
  `Error`; mandatory runtime при этом не использует пользовательский `Throwable` как mutable failure ledger;
- выбор terminal outcome на основании того, какой внутренний owner физически закрыл stdout/stderr;
- ожидание physical stream close как самостоятельную часть public exit, если процесс и обязательный scenario cleanup
  уже завершены;
- заранее запущенный publication owner или зарезервированный поток для каждой будущей lifecycle operation;
- доказательство отсутствия любого когда-либо существовавшего descendant: cleanup остаётся честным best effort;
- одинаковое поведение при зависшем пользовательском callback и при штатном отказе CLI protocol;
- наблюдение через public `closeAsync()` физического завершения позднего worker callback после logical pool close.

Для одного callback owner выполняется не более одной scenario-critical операции одновременно: adapter/decoder,
readiness, health/reset hook, regex или listener в зависимости от сценария. Если callback не реагирует на interruption
после deadline, его handle или worker становится terminal и не создаёт следующую callback-задачу. Daemon thread может
остаться до фактического возврата callback. Это per-handle containment, а не глобальный memory bound, который невозможно
честно гарантировать без общей admission-зависимости, в том числе при использовании virtual threads.

Отмена последовательных callback использует одну активную регистрацию, а не общий реестр подписчиков. Регистрация
снимается до возврата caller-у; позднее закрытие старой регистрации не может снять новую. Uncancellable операции не
создают cancellation signal.

Abandonment после deadline является logical settlement request callback и не ожидает его физического возврата. Выбранная
request failure может быть возвращена после такого settlement; terminal future всего scenario публикуется только после
bounded process termination и logical output-mode settlement. `ModeSettlement` line/protocol/Expect принадлежит
transport pumps, а не пользовательскому decoder, adapter или matcher: зависший callback не блокирует `onExit()` уже
завершившегося процесса, а поздняя request-local failure не переписывает опубликованный result. Физическое завершение
request callback или stream close не является publication gate.

Streaming listener имеет другой, явно пользовательский контракт: его вызовы синхронны и создают backpressure. При
natural process exit уже допущенная доставка chunk входит в logical settlement output mode, иначе библиотека могла бы
молча потерять прочитанный output. Explicit `close()` и timeout прекращают ожидание некооперативного listener и запускают
shutdown. Natural drain может ждать pipe, унаследованный живым потомком; абсолютный scenario timeout ограничивает это
ожидание. Таким образом, различие определяется семантикой сценария, а не внутренним порядком потоков.

Явный `Session.closeStdin()` сначала запрещает новые записи, затем обязан принять и запустить physical close через
bounded dispatcher. После успешного handoff метод не ждёт physical close. Admission/start failure выполняет bounded
terminal cleanup перед возвратом caller-у; отдельный async lifecycle только ради ускорения этой редкой infrastructure
ошибки не создаётся. Поздний failure выбирает terminal outcome только до выбора public outcome.

Живой process handle не резервирует close capacity. Terminal cleanup каждого ещё не закрытого stream делает одну попытку
попасть в bounded close dispatcher. Saturation или невозможность запустить task означает best-effort cleanup failure:
process termination остаётся обязательной, physical close может быть пропущен и результат не задерживается. Natural exit
не закрывает caller-owned raw stdout/stderr, чтобы непрочитанный хвост оставался доступен. Это устраняет скрытую
process-global квоту на число одновременно живых sessions и pools. Stdout/stderr совместно закрепляются за close owner,
но независимо допускаются в dispatcher: нехватка места для второго stream не отменяет закрытие первого. Атомарный допуск
пары не защищает пользовательский outcome и потому не требуется. Результат каждого close фиксируется обязательно;
отдельного канала уведомлений об успешном close нет.

Для file capture два существующих target сравниваются по реальной filesystem identity. Запрет имён, похожих на другой
файловой системе, не нужен, если текущая система уже установила различие файлов. Если хотя бы один target отсутствует,
консервативная нормализация имени сохраняется: до создания файлов их identity ещё неизвестна. Hardlink/symlink aliases
одного файла запрещены; одновременная замена/relink путей во время launch не поддерживается.

Доставка diagnostics является отдельной best-effort операцией. Она не входит в critical callback admission, не может
задерживать protocol, stream или pool operation и не меняет runtime outcome, включая construction, close и output
truncation.

Cleanup failure может быть suppressed exception или diagnostic event. Точная форма secondary failure graph не является
API-контрактом и не должна создавать отдельный state machine. `ShutdownFailureLedger` удерживает не более 32 исходных
ошибок одной shutdown operation и отдельно первое interruption, если оно возникло после заполнения лимита. Первая
выбранная причина сохраняется; interruption имеет прежний приоритет. Повторные observation failures не увеличивают
retention после лимита и не прекращают попытки cleanup. Это предел числа сохранённых источников, а не размера
переданного пользователем `Throwable` graph.

Custom `PtyProvider` является trusted SPI: `available()`, `description()`, `start(...)` и методы возвращённых
`Process`/`ProcessHandle` не имеют индивидуальной timeout/thread isolation. Metadata и signals должны возвращаться
promptly, timed waits — соблюдать
timeout. Зависший custom call может задержать session и cleanup за configured deadline. System provider отдельно
ограничивает собственные capability detection и bootstrap. Bounded scan и asynchronous destroy fallback сохраняются.

Только descendant scan имеет общую bounded admission: не более 32 выполняющихся операций с deadline ожидания.
`ProcessScanOperationOwner` после timeout/interruption прерывает уже запущенный disposable worker; slot остаётся занят
до физического возврата scan. Поздний result, включая Error, не переписывает выбранный outcome и не требует reporting
settlement. Своевременно полученный Error сохраняет identity. Это не квота на ordinary process calls.

`readTextExactly` читает и декодирует complete field ограниченными chunks. После обнаружения превышения local/global
character budget следующие chunks не читаются; предварительное чтение всего поля не требуется.
Ни partial text, ни успешный response не публикуются. Ошибка terminal, поэтому точная byte position после неё не является
гарантией. Успешное чтение по-прежнему расходует ровно объявленное число bytes и сохраняет следующий frame.

## Архитектура runtime

Runtime строится вокруг небольшого числа владельцев:

- `DefaultSession` координирует raw handle, но делегирует physical resources `SessionResources`, а process termination —
  `SessionProcessCleanup`;
- `SessionTerminal` хранит natural process outcome как fallback, первый non-exit primary claim, один
  `ModeSettlement` и publication flag; поздняя регистрация gates запрещена;
- `OutputPumpCoordinator` и `OutputPumpCleanup` являются mode-specific transport owner: они владеют pumps, transport
  drain и memory bounds и передают `SessionTerminal` один `ModeSettlement`; request callback владеет только
  синхронным request outcome. После process outcome и mode settlement output owner ровно один раз передаёт пару
  stdout/stderr на physical close; отдельных per-stream состояний готовности и pump-close callbacks нет;
- `ProcessTreeShutdown` выполняет одну последовательность `scan -> graceful -> bounded rescan/wait -> force -> wait`;
  rescan выполняется во время graceful phase и непосредственно перед force по root и уже найденным handles, поэтому
  может обнаружить descendant, созданный shutdown hook, но не обещает доказать отсутствие мгновенно переподчинённого
  процесса. Временный incomplete из-за caller budget относится к последнему combined refresh; последующий полный
  root + known-descendants refresh может разрешить completion. Известные живые handles, `UNAVAILABLE` и overflow
  не забываются; финальный scan и observation должны уложиться в deadline текущей фазы;
- `ProcessIoResources` и `ProcessStreamResource` обеспечивают stable stream identity и exact-once logical close;
- `TimedTaskRunner` ограничивает время ожидания одной пользовательской или потенциально блокирующей операции;
  cancellable session owner делает handle terminal через abandonment handler до прерывания callback, а остальные
  owners обрабатывают timeout/interruption до допуска следующей операции;
- `SerializedRequestGate` сериализует line/protocol requests; сценарные state owners сохраняют различия retryability и
  failure attribution;
- `WorkerPoolState` является одним consistency domain pool и выполняет внешние действия вне monitor.

Output mode всегда выбирается до launch: raw interactive, Expect, line, protocol, listen или run. Expect уже является
веткой `interactive().expect()`, а raw `Session` больше не создает helper. Non-raw handle и pumps создаются до открытия
construction gate exit watcher-а; `SessionOutputOwnership` хранит выбранный mode и проверяет единственный helper claim
и переход `PLANNED -> CLAIMED -> READY` после завершённого startup pumps до commit, но не выполняет transfer между raw и
helper.

### Terminal outcome

Для одного session scenario существует ровно один `SessionTerminal`. Scenario wrapper не создаёт второй terminal owner.
Он хранит primary claim, process outcome, причину успешного завершения (`NATURAL`, `CLOSED`, `TIMED_OUT`), фиксированный
`ModeSettlement` и publication flag.

| Claim | Когда может быть опубликован | Приоритет |
|---|---|---|
| launch/readiness failure | после bounded rollback начатого process lifecycle | во время construction выигрывает у exit |
| timeout/caller close | после process outcome и logical `ModeSettlement` | после открытия первый из timeout, close или transport failure стабилен |
| transport failure | после process outcome и logical `ModeSettlement` | после открытия первый из timeout, close или transport failure стабилен |
| process exit | хранится как process outcome и рассматривается после mode settlement | fallback, если non-exit primary claim нет |
| cleanup failure, известный до publication | вместе с основным outcome как bounded secondary detail | не заменяет основной outcome |

`SessionTerminal` сразу и стабильно фиксирует только первый non-exit primary claim. Natural process exit хранится как
fallback, поэтому malformed output или timeout обязательного drain не маскируются более ранним exit observation.
Settlement управляет только моментом публикации. `SessionTerminal` не знает framing, decoding, listener или process-tree
алгоритмы. Выбранный primary owner единолично выполняет bounded process cleanup; проигравший close не повторяет и не
ожидает его работу. После natural exit без primary owner первый late close/failure отдельно claims post-outcome cleanup
caller-owned raw resources и известных живых descendants; остальные late paths не повторяют и не ожидают эту работу.

Process outcome означает natural exit либо завершённую bounded termination attempt. Construction failure публикуется
только после rollback и не возвращает session handle. `ModeSettlement` включает нормальный transport drain либо logical
abandonment после shutdown. Возврат пользовательского matcher/decoder не является третьим settlement.

### Pool

Pool сохраняет две разные и практически полезные настройки:

- `warmupSize`: `open()` синхронно создаёт указанное число ready workers; failure закрывает все уже созданные workers и
  не возвращает частично открытый pool;
- `minIdle`: один background replenisher асинхронно устанавливает ready idle floor после `open()` и восстанавливает его
  после acquire/retirement. `minIdle == 0` отключает его. Отдельный `backgroundReplenishment` boolean удаляется как
  противоречивый дубликат policy.

Завершившийся ordinary replenishment failure увеличивает `failedStartups`, публикует bounded diagnostic и повторяется с
bounded backoff, пока pool открыт. Один `PoolReplenisher` хранит не более одной scheduled attempt своего pool; поэтому
очередь зависит от числа live pools, но не растёт от частоты failures. Backoff не занимает worker thread, а очередная
попытка выполняется на общем fixed-size scheduler. Поэтому failing pool не удерживает lifecycle owner непрерывным
циклом. Abandoned, физически незавершённый startup сохраняет занятый slot и не повторяется;
slot освобождается только после позднего возврата callback либо logical close всего pool. Поэтому один зависший attempt
не может породить неограниченную последовательность daemon owners. Ordinary failure не делает pool terminal даже при
нуле ready workers: acquire может выполнить собственный demand startup и получает его typed startup failure, а
background replenisher продолжает bounded retries. `Error` из background factory/readiness атомарно запускает
`OPEN -> CLOSING`, сохраняется причиной typed pool failure и никогда не повторяется. `close()` останавливает
replenishment и отменяет его pending scheduled turn; поздно созданный worker закрывается и никогда не попадает в idle.

Process-wide hard cap на число queued replenishment turns не обещается: число live pool instances принадлежит
приложению, и сохранение eventual `minIdle` для каждого из них требует помнить один pending turn на pool. Жёсткий cap
потребовал бы блокировать lifecycle caller, отбрасывать progress либо хранить отдельную overflow-очередь того же размера.
Вместо этого Procwright ограничивает execution parallelism, coalesces retries одного pool независимо от их частоты и
удаляет pending turn при logical close.

Health и reset являются разными lifecycle points. Отсутствующий hook хранится как отсутствие callback и не запускает
timed task; проверка завершения процесса перед выдачей worker сохраняется:

- health выполняется после acquire перед повторным использованием; только `false` retire worker и продолжает поиск в
  пределах исходного acquire deadline. Timeout, interruption или exception retire worker и немедленно возвращают typed
  pooled failure, чтобы поломка hook не маскировалась как `ACQUIRE_TIMEOUT`; `Error` не преобразуется;
- reset выполняется после успешного response; ordinary failure или timeout retire worker, но не заменяет уже полученный
  успешный response; `Error` не преобразуется и немедленно пробрасывается вызывающему коду;
- hooks доверенные и должны сотрудничать с interruption; неотзывчивый hook может оставить не более одного daemon owner
  для этого worker и не запускает для него следующую операцию.

Line pool проверяет разделители и размер запроса до acquire, но создаёт encoded byte array только после получения
worker. Обе фазы подготовки расходуют request budget; acquire wait из него исключён. Локальный failure до передачи
запроса session не меняет состояние протокола: lease возвращается без reset и без увеличения request count worker.
Обычные age/close retirement rules действуют и при таком возврате.

Late pool failures передаются напрямую одному `BoundedFailureReporter` через `PoolFailurePublisher`. Best-effort
уведомление не ждёт admission или доставки и не меняет выбранный lifecycle outcome. При насыщении либо недоступности
reporter уведомление может быть потеряно; отдельной очереди ожидания и повторной диспетчеризации нет. Обязательный
retirement сохраняет собственную bounded queue с caller-runs backpressure.

Минимальный автомат pool имеет четыре занятых состояния:

| Состояние | Допустимые переходы |
|---|---|
| `STARTING` | `IDLE` после readiness; завершившийся failure освобождает slot; abandoned attempt удерживает slot до позднего возврата или pool close; при close attempt отделяется от pool state, а поздний worker закрывается напрямую |
| `IDLE` | `LEASED` при acquire; `RETIRING` при close, age или policy retirement |
| `LEASED` | `IDLE` после успешных request и reset либо локального failure подготовки до session request; `RETIRING` после worker request failure, limit или close |
| `RETIRING` | завершённый close освобождает slot; возврата к ready состоянию нет |

Все переходы выполняются под одним monitor. Factory, readiness, hook, request, worker close и future completion
выполняются вне monitor. Active lease, возвращённый после `close()`, только retire; capacity retiring worker
освобождается после завершения его logical retirement.

Lifecycle самого pool имеет четыре состояния:

| Состояние | Правило |
|---|---|
| `CONSTRUCTING` | выполняется synchronous warmup; failure запускает bounded rollback и не публикует pool |
| `OPEN` | разрешены acquire/reservation и replenishment |
| `CLOSING` | достигается explicit close либо background `Error`; новые acquire/reservation запрещены; `STARTING` attempts отделяются и логически освобождают slots, `IDLE` retire, `LEASED` помечаются retire-on-return |
| `CLOSED` | нет logical leases и незавершённых обязательных logical retirements; physical callback completion не является gate |

`close()` использует один absolute deadline для ожидания leases и обязательной termination. По истечении deadline он
возвращает typed drain timeout, pool остаётся `CLOSING`, а уже запущенный cleanup продолжает приводить его к `CLOSED` без
участия caller. Поздний success отделённого startup напрямую закрывает worker, не помещает его в `RETIRING` и не меняет
pool state. Зависший factory/readiness callback не является close gate. Replenishment запускается только при
`idle + replenishmentStarting < minIdle && occupied < maxSize`.

Public `closeAsync()` сохраняется как cancellation-isolated view того же logical drain, который ожидает `close()`. Он не
создает второй cleanup, не является async request API и не наблюдает физический возврат abandoned callback или physical
stream close.

Обязательный смысл metrics не зависит от формы одного record:

- gauges: occupied size, idle, leased, starting и retiring;
- counters: created, retired, completed/failed requests, failed startups и failed worker closes;
- accumulated durations: acquire wait, request и успешный worker startup;
- retirement reasons: close, age, max requests, request/startup timeout or interruption, health/reset failure,
  decoder/protocol failure, process exit и прочий worker failure.

## Public API до первого выпуска

До первой публикации exact Java signature baseline не поддерживается: он не является обещанием совместимости
пользователям и не должен блокировать осознанное изменение поверхности.

До первого выпуска:

- сохраняются scenario-first grammar и контрольные пользовательские задачи, а не каждая текущая JVM signature;
- external consumer examples и public surface tests изменяются вместе с осознанным API-решением;
- после первого реального выпуска compatibility проверяется стандартным инструментом относительно опубликованного
  artifact.
