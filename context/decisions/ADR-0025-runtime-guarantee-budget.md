# ADR-0025: Бюджет гарантий runtime

## Статус

Принято, реализация выполняется.

Этот ADR задаёт целевую границу упрощения. Действующее поведение меняется только атомарным изменением кода, тестов,
public docs и proof map. До такого изменения текущий код и его black-box tests остаются источником истины.

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

## Гарантии, которые больше не являются целью

Runtime не обязан гарантировать:

- корректную работу с `Process`, `ProcessHandle`, `CharsetEncoder` или `CharsetDecoder`, нарушающими JDK contract;
- отдельный fresh non-inheriting thread и независимую process-global admission partition для каждого вида callback;
- fairness или FIFO между внутренними cleanup, reporting и callback tasks разных handles;
- восстановление служебного callback owner после его внутренней поломки;
- доставку ошибки callback, возникшей после abandonment, через JVM uncaught-exception handler;
- специальную identity-дедупликацию, неизменность исходного графа `Throwable` и продвижение позднего `Error`;
- выбор terminal outcome на основании того, какой внутренний owner физически закрыл stdout/stderr;
- ожидание physical stream close как самостоятельную часть public exit, если процесс и обязательный scenario cleanup
  уже завершены;
- заранее запущенный publication owner или зарезервированный поток для каждой будущей lifecycle operation;
- доказательство отсутствия любого когда-либо существовавшего descendant: cleanup остаётся честным best effort;
- одинаковое поведение при зависшем пользовательском callback и при штатном отказе CLI protocol;
- отдельный public `closeAsync()` и наблюдение физического завершения позднего worker callback после logical pool close.

Для одного callback owner выполняется не более одной scenario-critical операции одновременно: adapter/decoder,
readiness, health/reset hook, regex или listener в зависимости от сценария. Если callback не реагирует на interruption
после deadline, его handle или worker становится terminal и не создаёт следующую callback-задачу. Daemon thread может
остаться до фактического возврата callback. Это per-handle containment, а не глобальный memory bound, который невозможно
честно гарантировать на Java 17 без общей admission-зависимости.

Abandonment после deadline является logical settlement callback и не ожидает его физического возврата. Выбранная
request failure может быть возвращена после такого settlement; terminal future всего scenario публикуется только после
bounded process termination и logical output-mode settlement. Физическое завершение callback или stream close не
является publication gate.

Доставка diagnostics является отдельной best-effort операцией. Она не входит в critical callback admission, не может
задерживать protocol, stream или pool operation и никогда не меняет уже выбранный или опубликованный terminal outcome.

Cleanup failure может быть suppressed exception или diagnostic event. Точная форма secondary failure graph не является
API-контрактом и не должна создавать отдельный state machine.

## Целевая архитектура

Runtime строится вокруг небольшого числа владельцев:

- `ManagedProcess` владеет только process lifecycle: процессом, stdin, exit observation и запуском termination;
- `TerminalArbiter` принимает terminal claims, но публикует ровно один outcome только после двух известных при
  construction входов: одного `ProcessSettlement` и одного `ModeSettlement`; поздняя регистрация gates запрещена;
- `OutputMode` является единственным mode-specific owner: он владеет pump, framing, decoding, listener и memory bounds и
  отдаёт arbiter один `ModeSettlement`;
- `ProcessTerminator` выполняет одну последовательность `scan -> graceful -> bounded rescan/wait -> force -> wait`;
  rescan выполняется во время graceful phase и непосредственно перед force по root и уже найденным handles, поэтому
  может обнаружить descendant, созданный shutdown hook, но не обещает доказать отсутствие мгновенно переподчинённого
  процесса;
- `OwnedStreams` обеспечивает один consumer на stdout/stderr и exact-once logical close;
- `TimedOperation` ограничивает одну пользовательскую или потенциально блокирующую операцию с честным per-handle
  containment без глобальных callback lanes и late-failure protocol;
- `SerializedRequestExecutor` задаёт общий admission/deadline contract line и protocol sessions;
- `WorkerPool` является одним consistency domain с простыми состояниями worker и выполняет внешние действия вне monitor.

Output mode всегда выбирается до launch: raw interactive, Expect, line, protocol, listen или run. `Session.expect()` и
dynamic raw-to-helper ownership удаляются; Expect становится веткой `interactive().expect()`. `OwnedStreams` после этого
задаёт статическую topology и logical close, а не поддерживает transfer protocol.

### Terminal outcome

Для одного scenario существует ровно один `TerminalArbiter`. Scenario wrapper не создаёт второй arbiter. Arbiter хранит
только primary claim, фиксированные `ProcessSettlement` и `ModeSettlement`, а также publication flag.

| Claim | Когда может быть опубликован | Приоритет |
|---|---|---|
| launch/readiness failure | после bounded rollback начатого process lifecycle | во время construction выигрывает у exit |
| timeout/caller close | после `ProcessSettlement` и logical `ModeSettlement` | после открытия первый из timeout, close или transport failure стабилен |
| transport failure | после `ProcessSettlement` и logical `ModeSettlement` | после открытия первый из timeout, close или transport failure стабилен |
| process exit | хранится в `ProcessSettlement` и рассматривается после обоих settlements | fallback, если non-exit primary claim нет |
| cleanup failure, известный до publication | вместе с основным outcome как bounded secondary detail | не заменяет основной outcome |

Arbiter сразу и стабильно фиксирует только первый non-exit primary claim. Natural process exit хранится внутри
`ProcessSettlement` как fallback, поэтому malformed output или timeout обязательного drain не маскируются более ранним
exit observation. Settlement управляет только моментом публикации. Arbiter не знает framing, decoding, listener или
process-tree алгоритмы.

`ProcessSettlement` означает один из трёх исходов: natural exit observed; bounded termination attempt completed; launch
не состоялся, а rollback завершён. `ModeSettlement` включает нормальный drain либо logical abandonment после deadline.

### Pool

Pool сохраняет две разные и практически полезные настройки:

- `warmupSize`: `open()` синхронно создаёт указанное число ready workers; failure закрывает все уже созданные workers и
  не возвращает частично открытый pool;
- `minIdle`: один background replenisher поддерживает ready idle capacity после retirement. `minIdle == 0` отключает
  его. Отдельный `backgroundReplenishment` boolean удаляется как противоречивый дубликат policy.

Завершившийся ordinary replenishment failure увеличивает `failedStartups`, публикует bounded diagnostic и повторяется с
bounded backoff, пока pool открыт. Abandoned, физически незавершённый startup сохраняет занятый slot и не повторяется;
slot освобождается только после позднего возврата callback либо logical close всего pool. Поэтому один зависший attempt
не может породить неограниченную последовательность daemon owners. Ordinary failure не делает pool terminal даже при
нуле ready workers: acquire может выполнить собственный demand startup и получает его typed startup failure, а
background replenisher продолжает bounded retries. `Error` из background factory/readiness атомарно запускает
`OPEN -> CLOSING`, сохраняется причиной typed pool failure и никогда не повторяется. `close()` останавливает
replenishment; поздно созданный worker закрывается и никогда не попадает в idle.

Health и reset являются разными lifecycle points:

- health выполняется после acquire перед повторным использованием; только `false` retire worker и продолжает поиск в
  пределах исходного acquire deadline. Timeout, interruption или exception retire worker и немедленно возвращают typed
  pooled failure, чтобы поломка hook не маскировалась как `ACQUIRE_TIMEOUT`; `Error` не преобразуется;
- reset выполняется после успешного response; ordinary failure или timeout retire worker, но не заменяет уже полученный
  успешный response; `Error` не преобразуется и немедленно пробрасывается вызывающему коду;
- hooks доверенные и должны сотрудничать с interruption; неотзывчивый hook может оставить не более одного daemon owner
  для этого worker и не запускает для него следующую операцию.

Минимальный автомат pool имеет четыре занятых состояния:

| Состояние | Допустимые переходы |
|---|---|
| `STARTING` | `IDLE` после readiness; завершившийся failure освобождает slot; abandoned attempt удерживает slot до позднего возврата или pool close; при close attempt отделяется от pool state, а поздний worker закрывается напрямую |
| `IDLE` | `LEASED` при acquire; `RETIRING` при close, age или policy retirement |
| `LEASED` | `IDLE` после успешных request и reset; `RETIRING` после failure, limit или close |
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

Public `closeAsync()` удаляется. Он наблюдал физическое завершение callback после logical close и создавал отдельный
completion protocol, хотя async request API не входит в библиотеку. Идемпотентный bounded `close()` и terminal metrics
являются единственным public close contract.

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

## Порядок реализации

1. Сохранить black-box proofs контрольных пользовательских задач.
2. Удалить преждевременный compatibility freeze и проверки внутренних обещаний, не являющихся целью.
3. Упростить lifecycle publication, physical close и late failure reporting.
4. Свести process termination и tree cleanup к честному best-effort владельцу.
5. Выбирать output owner до launch и упростить direct session runtime.
6. Объединить line/protocol admission и deadline вокруг одной операции на request.
7. Перестроить pool вокруг одного state owner, сохранив `warmupSize`, `minIdle` и hooks.
8. Упростить diagnostics, transcript values и optional PTY boundary только после runtime-срезов, которые докажут пользу.
9. Удалить тесты прежней внутренней формы после появления более сильных black-box и локальных invariant tests.
10. Сжать proof map, context и docs до остающихся пользовательских контрактов.

## Проверка

Каждый вертикальный срез проходит:

- релевантные unit и black-box integration tests;
- bounded stress tests для timeout, cleanup, memory и pool contention;
- API consumer compilation;
- JMH и memory comparison для затронутого сценария;
- независимый аудит сохранения пользовательских гарантий;
- независимый аудит реального уменьшения числа состояний, переходов и владельцев.

До и после каждого среза фиксируются owners, mutable states, transitions, monitor domains, служебные threads и completion
edges. Обязательные архитектурные gates: нет process-global callback lanes, prestarted publication threads и второго
terminal arbiter в scenario wrappers.

LOC не является gate, но итоговое упрощение должно дать заметное сокращение production и test code. Этап не считается
успешным, если число типов уменьшилось за счёт giant controller либо если одновременно удерживаемые инварианты, служебные
потоки или внутренние переходы не уменьшились.

Context меняется атомарно с соответствующим вертикальным срезом:

- ADR-0021 переписывается до остающихся `CommandSpec`, `LaunchPlan` и one-shot result contracts вместе с новым runtime;
- ADR-0023 удаляется при переносе pool на минимальный автомат из этого ADR;
- ADR-0024 удаляется при переходе на pre-launch output modes.
