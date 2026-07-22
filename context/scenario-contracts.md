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
- `Session.expect()`.

Все configuration objects в этих путях — immutable persistent Draft. Методы `with*` fail fast для локально
невалидного скалярного значения и возвращают новый snapshot. Проверки, зависящие от сочетания settings или
нормализованного plan, выполняются в `execute()`/`open()` до запуска процесса; поэтому Draft может временно хранить
несогласованные поля. `execute()`/`open()` являются явными resource terminals. Presets, Kotlin extensions и integrations
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
- timeout включает input writing, ожидание процесса, output drain и bounded cleanup;
- non-zero exit остается `CommandResult`; launch, supervision, I/O и decode failures — `CommandExecutionException`.

Caller выбирает timeout, capture budget, charset policy, input, output mode и shutdown escalation.

## `interactive`

`InteractiveScenario.Draft.open()` возвращает raw `Session`.

Гарантии:

- `close()` идемпотентен;
- `closeStdin()` и session cleanup используют bounded close capacity и не ждут бесконечно заблокированный stream;
- `onExit()` завершается после process exit и cleanup;
- idle timeout учитывает caller-visible I/O activity;
- terminal policy относится только к session family;
- readiness probe выполняется до возврата handle;
- первая raw stdout/stderr operation выбирает raw ownership mode;
- после helper claim raw stream wrappers отклоняют read/close, а helper claim после raw operation fail fast.

Caller владеет parsing и ordering raw protocol. Для сериализованного line/typed workflow используются отдельные
сценарии.

## `Session.expect()`

`Session.expect()` возвращает неизменяемый `Expect.Draft` и не захватывает output ownership; каждый `with*` создает новую
ветку. `Expect.Draft.open()` захватывает оба output streams. Получение raw wrapper само по себе не конфликтует с `open()`,
но первая фактическая raw operation, другой helper claim или session cleanup приводят к `IllegalStateException`.

Гарантии:

- literal/regex matching имеет bounded timeout и match buffer;
- transcript bounded и доступен в `ExpectException`;
- send/expect values редактируются в transcript по умолчанию;
- EOF, timeout, closed session и read failure имеют разные reasons;
- встроенное incremental stripping для 7-bit CSI sequences с префиксом `ESC [` применяется до matching и transcript
  retention, ведет независимое bounded state для stdout/stderr и сохраняет incomplete, malformed и overlong candidates
  как text;
- закрытие `Expect` закрывает underlying `Session` и не возвращает output streams raw caller;
- concurrent `open()` одного `Expect.Draft` не может создать двух владельцев: только один claim успешен.

## `lineSession`

`LineSessionScenario.Draft.open()` возвращает `LineSession` с одним request/response cycle за раз.

Гарантии:

- request deadline охватывает validation, bounded encoding, lock acquisition, write и decode;
- request byte/char, response line/char, unfinished-line и pending backlog limits независимы;
- LF/CRLF и unfinished EOF line учитываются по содержимому; trailing `\r` без `\n` является содержимым;
- incremental decoder ограничивает undecoded input и output без input consumption;
- custom `ResponseDecoder` единолично определяет завершение response;
- stderr дренируется в bounded transcript;
- timeout, EOF, broken pipe, decode, oversize и backlog overflow различаются;
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
- stdout/stderr backlog и transcript retention ограничены независимо;
- strict/replace charset behavior выбирается явно;
- timeout, EOF, broken pipe, decode, oversized data, backlog overflow и adapter failure имеют стабильные reasons;
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
- `onExit()` завершается после process exit и pump completion;
- retained diagnostics bounded.

Listener должен быстро завершаться; тяжелая обработка выносится во внешнюю bounded очередь/executor.

## Пулы line/protocol sessions

`Draft.pooled()` фиксирует worker snapshot и возвращает unopened `PoolDraft`. `PoolDraft.open()` создает pool.

Гарантии:

- pool переиспользует direct line/protocol runtime;
- lease не раскрывается;
- live worker находится ровно в одном состоянии: starting, idle, leased или retiring;
- `withMaxSize` сразу отклоняет неположительный `maxSize`; `PoolDraft.open()` до запуска workers отклоняет
  `maxSize > 256`, `warmupSize > maxSize`, `minIdle > maxSize` и `minIdle > 0` без background replenishment;
- после проверки в terminal `maxSize` ограничивает все live slots одного pool, включая startup/retirement, и не
  резервирует process-wide capacity;
- независимый process-wide worker admission допускает суммарно не более 256 workers всех pools; admission захватывается
  до worker factory и удерживается через startup/live/retirement до завершения physical close;
- независимый process-wide pool-completion admission удерживает не более 256 completion owners/pools одновременно; он
  захватывается до запуска completion owner и warmup, удерживается до terminal completion и не расходует worker admission;
- завершившийся close, включая close с ошибкой, освобождает worker admission, а незавершившийся close сохраняет
  backpressure; порядок получения освободившегося admission разными pools не является контрактом;
- warmup failure закрывает уже созданных workers;
- worker становится idle только после readiness;
- acquire timeout и request timeout различаются;
- request duration metric не включает acquire wait;
- timeout, protocol/decoder failure и process exit retire worker;
- reset выполняется после успешного response, health — перед повторным использованием;
- hook timeout ограничивает reset/health;
- `maxRequestsPerWorker`, `maxWorkerAge`, `minIdle` и background replenishment не раскрывают lifecycle caller-у;
- `close()` bounded синхронно запрещает новые requests, закрывает idle workers и ждет retirement активных после request;
- `closeAsync()` запускает тот же terminal cleanup и возвращает cancellation-isolated future;
- `DRAIN_TIMEOUT` не отменяет cleanup; failed worker close дает `WORKER_FAILED` и остается видимым в metrics/outcome;
- retirement dispatch bounded; saturation observable и не создает новый thread;
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
- public suspending `openAwait()` отсутствует, пока ownership при startup/cancellation race не доказан.

## Optional integrations

JSON Lines, delimiter, Content-Length и typed JSON adapters используют существующие `lineSession`/`protocolSession`,
а command-backed tool helpers — `run`. Они валидируют frame size, depth и syntax до domain use, считают CLI output
недоверенными данными и не включают raw unbounded diagnostics в structured errors.

## Gate

Изменение контракта требует одновременно:

- behavior test на public API;
- обновления exact public signature baseline;
- компиляции всех внешних consumer fixtures;
- обновления этого документа и [quality/invariant-proof-map.md](quality/invariant-proof-map.md);
- отдельного ADR, если меняется ownership, lifecycle или package/module boundary.
