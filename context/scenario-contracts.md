# Контракты scenario-first API

## Общий контракт

Канонические пути:

- `Procwright.command(...).run()`;
- `Procwright.command(...).interactive()`;
- `Procwright.command(...).lineSession()`;
- `Procwright.command(...).protocolSession(adapterFactory)`;
- `Procwright.command(...).listen()`;
- `lineSession().pooled()`;
- `protocolSession(adapterFactory).pooled()`;
- `interactive().expect()`.

Все configuration objects в этих путях — immutable persistent Draft. Методы `with*` fail fast для локально
невалидного скалярного значения и возвращают новый snapshot. Проверки, зависящие от сочетания settings или
нормализованного plan, выполняются в `execute()`/`open()` до запуска процесса; поэтому Draft может временно хранить
несогласованные поля. `execute()`/`open()` являются явными resource terminals. Kotlin extensions и integrations
не создают параллельный API запуска.

Общие гарантии runtime:

- direct argv и inherited environment являются compatibility defaults; shell и clean environment включаются явно;
- timeout, close и failure используют общую shutdown policy и process-tree cleanup;
- stdout/stderr draining, retention и очереди ограничены владельцем сценария;
- readiness завершается до возврата session/worker;
- result/failure сохраняет безопасный диагностический snapshot;
- raw argv/env и unbounded output не попадают в сообщения по умолчанию;
- interruption и cancellation не обходят cleanup;
- `Duration.ZERO` отключает те timeout, контракт которых допускает отсутствие deadline; отрицательные значения
  отклоняются до запуска.

## `run`

`RunScenario.Draft.execute()` запускает конечную команду.

Гарантии:

- stdin закрывается, если input не задан;
- stdout/stderr дренируются параллельно;
- capture bounded и независимо помечает truncation;
- discard/file capture перенаправляется на уровне ОС без pump retention;
- strict decoding возвращает typed `DECODE_ERROR`, а raw captured bytes остаются доступными;
- non-progressing или нарушающий charset contract decoder не может бесконечно удерживать runtime;
- один absolute timeout включает input writing, ожидание процесса и output drain; после его выбора обязательный
  process-tree cleanup ограничен shutdown policy;
- готовый result не ждёт поздний physical close уже логически закрытых process streams;
- non-zero exit остается `CommandResult`; launch, supervision, I/O и decode failures — `CommandExecutionException`.

Caller выбирает timeout, capture budget, charset policy, input, output mode и shutdown escalation.

## `interactive`

`InteractiveScenario.Draft.open()` возвращает raw `Session`.

Гарантии:

- `close()` идемпотентен;
- concurrent/repeated `close()` не присоединяется к cleanup уже выбранного terminal owner; `onExit()` является
  completion barrier логического outcome;
- `closeStdin()` и session cleanup используют bounded close capacity и не ждут бесконечно заблокированный stream;
- `onExit()` завершается после process outcome и logical settlement выбранного output mode; potentially blocking
  physical stream close выполняется независимо;
- idle timeout учитывает caller-visible I/O activity;
- terminal policy относится только к session family;
- readiness probe выполняется до возврата handle;
- raw output mode выбирается до launch и не может быть заменен helper mode после открытия.

Caller владеет parsing и ordering raw protocol. Для сериализованного line/typed workflow используются отдельные
сценарии.

Custom `PtyProvider` и возвращённые им process objects являются trusted SPI без индивидуальной per-call isolation.
Metadata/signal calls должны
возвращаться promptly, timed waits — соблюдать timeout; зависшая реализация может превысить session/cleanup deadline.
Bounded descendant scans и asynchronous destroy fallback сохраняются. System provider сохраняет собственные bounded
capability detection и bootstrap.

У line/protocol/Expect natural `onExit()` дополнительно ждёт drain принадлежащих helper потоков вывода.
Request/match timeout не является absolute drain timeout; idle watcher прекращает ожидание после process outcome.
Если root завершился, но потомок удерживает pipe, caller ограничивает ожидание future и явно закрывает handle при
истечении этого срока. Таймаут future сам по себе handle не закрывает. Явный close логически завершает drain и запускает
bounded cleanup, не ожидая физического возврата каждого stream close.

## `interactive().expect()`

`interactive().expect()` возвращает неизменяемый `ExpectScenario.Draft`; каждый `with*` создает новую ветку.
`open()` запускает независимый процесс с Expect output mode, выбранным до launch. Raw output API в этой ветке отсутствует.

Гарантии:

- literal/regex matching имеет bounded timeout и match buffer;
- transcript bounded и доступен в `ExpectException`;
- send/expect values редактируются в transcript по умолчанию;
- EOF, timeout, closed Expect handle и read failure имеют разные reasons;
- timeout ожидания output или matcher slot допускает следующий match; abandonment незавершённой regex evaluation
  делает handle terminal и запрещает новые matcher tasks, поэтому один reason `TIMEOUT` не доказывает retryability;
- `closeStdin()` посылает EOF без остановки процесса и matcher;
- input/output используют общий charset по умолчанию, но output может иметь отдельный явный override;
- встроенное incremental stripping для 7-bit CSI sequences с префиксом `ESC [` применяется до matching и transcript
  retention, ведет независимое bounded state для stdout/stderr и сохраняет incomplete, malformed и overlong candidates
  как text;
- закрытие `Expect` закрывает его процесс;
- concurrent `open()` одного `ExpectScenario.Draft` создает независимые процессы.

## `lineSession`

`LineSessionScenario.Draft.open()` возвращает `LineSession` с одним request/response cycle за раз.

Гарантии:

- request deadline охватывает validation, bounded encoding, lock acquisition, write и decode;
- request byte/char и response line/char limits независимы; pending stdout использует response line/char limits,
  а незавершённая строка — response char limit;
- LF/CRLF и unfinished EOF line учитываются по содержимому; trailing `\r` без `\n` является содержимым;
- incremental decoder ограничивает undecoded input и output без input consumption;
- custom `ResponseDecoder` единолично определяет завершение response;
- stderr дренируется в bounded transcript;
- timeout, EOF, broken pipe и decode различаются; oversize ответа, частичной строки или очереди даёт `RESPONSE_TOO_LARGE`;
- validation, request-size, encoding и ожидание сохраняют session, если request не передан на stdin write и гарантированно
  не сможет записаться позже;
- после передачи request writer-у timeout, interruption или write failure закрывает session, даже если факт получения
  первого byte процессом неизвестен;
- response и остальные protocol failures закрывают session.

Caller выбирает decoder и limits, соответствующие worker protocol.

## `protocolSession`

`ProtocolSessionScenario.Draft.open()` возвращает typed `ProtocolSession<I, O>`.

Гарантии:

- factory создает отдельный `ProtocolAdapter<I, O>` до запуска каждого session/worker;
- concurrent terminal calls могут вызывать factory конкурентно; factory должна быть thread-safe;
- adapter factory `null`, `RuntimeException` и `Error` не запускают процесс и сохраняют исходный failure;
- один request/response cycle выполняется одновременно;
- `ProtocolWriter` и `ProtocolReaders` применяют единый request deadline;
- request/response byte и char limits глобальны для всего response, даже если adapter делает несколько reads;
- каждая stdout/stderr queue ограничена `maxResponseBytes`, общий response budget суммирует bytes, прочитанные с обоих
  streams; transcript retention остаётся независимым;
- strict/replace charset behavior выбирается явно;
- `readTextExactly` декодирует complete field ограниченными chunks и прекращает чтение при character-limit failure,
  не дочитывая остаток поля; точная byte position после terminal failure не обещается;
- timeout, EOF, broken pipe, decode, oversized data и adapter failure имеют стабильные reasons; превышение response или
  соответствующей output queue даёт `RESPONSE_TOO_LARGE`, stderr overflow становится ошибкой только при чтении adapter-ом;
- protocol failure закрывает session.

Adapter владеет framing и domain decoding. Runtime владеет процессом, readers/writer, deadline, bounds и diagnostics.

## `listen`

`StreamScenario.Draft.open()` возвращает `StreamSession`.

Гарантии:

- stdout/stderr дренируются параллельно;
- listener callbacks сериализованы;
- медленный callback создает pipe backpressure, а не unbounded очередь;
- stdin всегда закрывается на старте; для записи в stdin используется `interactive()`;
- timeout и любой listener failure, включая `Error`, проходят через общий shutdown path;
- reason различает listener, output-read и process failure;
- construction failure после launch закрывает уже открытый процесс;
- при natural exit `onExit()` ждёт завершения уже допущенной синхронной listener delivery, чтобы не терять прочитанный
  chunk;
- explicit close и timeout не ждут listener, который игнорирует interruption;
- potentially blocking physical close выполняется best effort и независимо от `onExit()`;
- retained diagnostics bounded.

Listener должен быстро завершаться; тяжелая обработка выносится во внешнюю bounded очередь/executor.

## Пулы line/protocol sessions

`Draft.pooled()` фиксирует worker snapshot и возвращает unopened `PoolDraft`. `PoolDraft.open()` создает pool.

Гарантии:

- pool переиспользует direct line/protocol runtime;
- lease не раскрывается;
- live worker находится ровно в одном состоянии: starting, idle, leased или retiring;
- line и protocol pools возвращают общий `PooledSessionMetrics` и используют общий `PooledSessionException` для
  acquisition/startup/hooks/close; request failures остаются `LineSessionException` или `ProtocolSessionException`;
- `withMaxSize` сразу отклоняет неположительный `maxSize`; `PoolDraft.open()` до запуска workers отклоняет
  `maxSize > 256`, `warmupSize > maxSize` и `minIdle > maxSize`;
- после проверки в terminal `maxSize` ограничивает все slots одного pool: starting, idle, leased и retiring; значение по
  умолчанию — 1, допустимый диапазон — от 1 до 256;
- разные pools и direct sessions не делят process-global worker quota; приложение ограничивает суммарное число процессов
  количеством создаваемых pools/direct sessions и `maxSize` каждого pool;
- незавершившийся retirement продолжает занимать slot своего pool до полного retirement outcome;
- `maxSize` ограничивает одновременно starting workers одного pool; независимые pools не делят startup admission.
  Ожидание hooks ограничено deadline; некооперативный callback может завершиться позднее, но worker после timeout не
  переиспользуется. Retirement processing использует fixed owner set и bounded queue с caller-runs backpressure;
- warmup failure закрывает уже созданных workers;
- worker становится idle только после readiness;
- acquire timeout и request timeout различаются;
- request duration metric не включает acquire wait;
- timeout/failure обращения к worker, включая protocol/decoder failure и process exit, retire worker; локальная
  подготовка line request до обращения к session возвращает незатронутый worker без reset и расходования request limit;
- reset выполняется после успешного response, health — перед повторным использованием;
- hook timeout ограничивает заданные reset/health; отсутствующие hooks не запускают timed task;
- `maxRequestsPerWorker`, `maxWorkerAge` и `minIdle` не раскрывают lifecycle caller-у; `minIdle == 0` отключает
  replenishment, положительное значение включает его;
- `close()` bounded синхронно запрещает новые requests, закрывает idle workers и ждет retirement активных после request;
- `closeAsync()` запускает тот же terminal cleanup и возвращает cancellation-isolated future;
- `DRAIN_TIMEOUT` не отменяет cleanup; failed worker close дает `WORKER_FAILED` и остается видимым в metrics/outcome;
- retirement processing использует fixed owner set и bounded queue, а saturation выполняет обязательный step на caller
  thread без fallback thread; terminal outcome выбирается под pool monitor и публикуется после его освобождения;
- metrics дают согласованный snapshot counters, durations, live states и retire reasons.

Protocol pool дополнительно гарантирует отдельный adapter на worker. Persistent branches разделяют factory reference,
но не adapter state.

## Diagnostics

Scenario Draft подключает `DiagnosticListener` и `DiagnosticTranscriptSink` напрямую через `with*`.

- diagnostics не изменяет command/session outcome;
- доставка best-effort и bounded;
- recipients одного lifecycle получают events последовательно;
- failures recipients не влияют на process runtime;
- `runId` коррелирует события одного lifecycle;
- schema допускает только определенные event attributes и безопасный command echo.

## Kotlin

- duration extensions возвращают новый Java Draft;
- suspending run/request terminals выполняют blocking runtime вне caller coroutine;
- cancellation direct line request до stdin handoff оставляет session пригодной для повторного вызова, после handoff
  закрывает session; direct protocol request остается retryable только во время ожидания serialized slot, после его
  получения cancellation terminal; pooled request после lease retire-ит worker, acquire cancellation не затрагивает
  worker;
- cancellation `awaitExit()` отменяет только waiter;
- `openFlow()` cold и создает отдельную `StreamSession` на collection;
- cancellation collector закрывает только принадлежащую ему session;
- `openFlow()` имеет один cleanup path: вторичный сбой close не заменяет исходную failure или cancellation и не
  изменяет переданный `Throwable`; при отсутствии первичной ошибки сбой cleanup завершает collection ошибкой;
- public suspending `openAwait()` отсутствует, пока ownership при startup/cancellation race не доказан.

## Optional integrations

JSON Lines, delimiter, Content-Length и typed Jackson adapters используют существующий `protocolSession`. Они
валидируют frame size, depth, UTF-8 и syntax до domain mapping; lifecycle, timeout и bounded diagnostics остаются у core.

## Gate

Изменение контракта требует одновременно:

- behavior test на public API;
- обновления public boundary tests и [release/public-api-boundary.md](release/public-api-boundary.md), если меняется API;
- компиляции всех внешних consumer fixtures;
- обновления этого документа и [quality/invariant-proof-map.md](quality/invariant-proof-map.md);
- отдельного ADR, если меняется ownership, lifecycle или package/module boundary.
