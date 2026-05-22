# Контракты scenario-first API

## Назначение

Этот документ фиксирует public contracts канонических сценариев iCLI. Он описывает только реализованное поведение,
которое подтверждается тестами или compile-tested examples. Release gates проверяют, что API, документация, примеры и
тесты остаются согласованными. Если будущая возможность не укладывается в эти контракты, она требует нового ADR или
изменения существующего сценария.

Канонические сценарии core:

- `run`;
- `lineSession`;
- `protocolSession`;
- `interactive`;
- `expect`;
- `listen`;
- `lineSession().pooled()`;
- `protocolSession(factory).pooled()`.

Terminal/PTY — capability внутри `interactive` и `lineSession`, а не отдельный сценарий core. Command-backed tools —
optional integration layer поверх process scenarios, а не часть core process workflow.
`ScenarioPresets` — typed customizers для существующих scenario builders, а не отдельный сценарий.
Pooled workers наследуют terminal capability только через `LineSession` или `ProtocolSession`; отдельного PTY pool
runtime нет.

## Общие правила всех сценариев

- Builder/lambda остается draft layer.
- Resolver/domain layer превращает draft в валидированный invocation/execution plan.
- Runtime получает только валидированный plan.
- Timeout, explicit close, bounded retention, stream draining, cleanup, diagnostics и lifecycle не перекладываются на
  пользователя; integration-level cancellable calls описываются в optional integration layer.
- Timeout, explicit close и failure shutdown завершают process tree в пределах возможностей JDK `ProcessHandle`, а не
  только direct child process.
- Environment inheritance является явной policy: default совместимости — inherit, для недоверенных CLI доступен
  `cleanEnvironment()` с allowlist-style overrides через `putEnvironment(...)`.
- Public results и exceptions должны сохранять диагностически важный контекст.
- Backend-specific и dependency-specific types не попадают в core public API.
- Output ownership у интерактивных сценариев единственный: первая операция над публичным stdout/stderr выбирает raw
  stream mode, а claim higher-level helper выбирает helper mode. Второй mode после этого fail fast вместо
  конкурирующего чтения из того же процесса.

## `run`

One-shot execution для команды, которая должна завершиться и вернуть результат.

Библиотека гарантирует:

- stdin закрывается, если input не задан явно;
- stdout и stderr дренируются параллельно;
- capture bounded по умолчанию и выставляет truncation flags;
- timeout проходит через `ShutdownPolicy`;
- `CommandResult` различает exit code, timeout, stdout/stderr и elapsed time;
- non-zero exit остается результатом процесса, а launch/supervision/capture failure становится
  `CommandExecutionException`.

Пользователь отвечает за:

- интерпретацию domain-level exit code;
- выбор capture limit, если default недостаточен;
- выбор shell mode только когда shell semantics действительно нужны.

Граница scenario:

- `run` не запрашивает terminal capability;
- если CLI требует terminal или долгий interactive lifecycle, нужно использовать `interactive` или `lineSession`.

## `lineSession`

Line-oriented request/response workflow для REPL-like процессов.

Библиотека гарантирует:

- один request/response cycle декодируется за раз;
- response decoder владеет правилом завершения ответа;
- request timeout относится к одному циклу;
- stdout backlog bounded отдельно от transcript window;
- одна незавершенная stdout line bounded отдельной `maxLineChars` policy;
- stderr дренируется в transcript для diagnostics;
- timeout, EOF и decoder/read failure различаются;
- failure закрывает session, чтобы не продолжать работу в неизвестном protocol state.
- публичные raw stdout/stderr underlying session больше не читаются после того, как output передан `LineSession`;
- `LineSession` не создается, если public stdout/stderr уже выбрал raw stream mode.

Пользователь отвечает за:

- выбор decoder, соответствующего протоколу процесса;
- отсутствие заранее запущенных конкурирующих raw reads перед созданием `LineSession`;
- protocol reset/health semantics, если процесс используется через pool.

Граница scenario:

- terminal capability допустима как `TerminalPolicy` только если line protocol действительно работает под terminal
  transport;
- dependency-specific expect/PTY APIs не должны подменять line-session contract.

## `protocolSession`

Generic request/response workflow для framed, multi-line, byte-oriented или typed worker protocols.

Библиотека гарантирует:

- один request/response cycle декодируется за раз;
- caller-provided `ProtocolAdapter<I, O>` владеет request writer и response decoder;
- request writer может писать `byte[]` или `String`, а не только одну line;
- response decoder читает stdout/stderr через deadline-aware readers;
- request timeout относится к одному циклу;
- request bytes/chars и response bytes/chars имеют отдельные limits;
- output backlog bounded per stream;
- strict charset policy может fail fast с `DECODE_ERROR` вместо silent replacement;
- transcript bounded и маркирует truncated/malformed/redacted state;
- timeout, EOF, broken pipe, decode failure, oversized request/response, output backlog overflow и protocol decoder
  failure различаются;
- failure закрывает session, чтобы не продолжать работу в неизвестном protocol state;
- readiness probe выполняется после launch и до возврата `ProtocolSession`.

Пользователь отвечает за:

- protocol framing и domain decoding внутри adapter;
- выбор size limits, соответствующих протоколу;
- reset/health semantics, если процесс используется через pool;
- отсутствие конкурирующих raw reads перед созданием `ProtocolSession`.

Граница scenario:

- `protocolSession` не является raw stream parser API;
- common adapters живут в optional `:icli-integrations`, а не создают второй process runtime.

## `interactive`

Raw interactive process handle для caller-driven protocol.

Библиотека гарантирует:

- explicit lifecycle через `close()`, `closeStdin()` и `onExit()`;
- `close()` idempotent;
- stdin защищен от записи после close/closed stdin;
- idle timeout и explicit close проходят через общий shutdown path;
- raw stdout/stderr защищены от чтения/закрытия после передачи output higher-level helper;
- первая операция над raw stdout/stderr фиксирует raw stream mode и запрещает поздний helper claim;
- terminal capability выражается через `TerminalPolicy`, а transport details остаются за provider boundary.

Пользователь отвечает за:

- ordering, parsing и protocol state;
- выбор одного output access model: raw чтение или передача ownership higher-level helper;
- создание helper до первого raw output operation, если нужны гарантии `Expect`, `LineSession` или `StreamSession`;
- то, что raw streams не имеют line-session serialization guarantees.

Граница scenario:

- `interactive` предоставляет raw handle, но не становится generic process-builder flag surface;
- higher-level automation должна идти через `Expect` или `LineSession`, если нужны их гарантии.

## `expect`

Prompt automation helper поверх уже открытого `Session`.

Библиотека гарантирует:

- `Expect` владеет output streams underlying session на уровне iCLI helpers: второй helper не может забрать output
  ownership;
- literal/regex matching имеет bounded timeout;
- transcript bounded и попадает в `ExpectException`;
- caller-provided send/expect values в transcript redacted by default; verbatim transcript values требуют явного opt-in;
- EOF, timeout, closed и read failure различаются;
- optional output filters применяются перед matching и transcript retention;
- closing `Expect` closes underlying `Session`;
- публичные raw stdout/stderr underlying session больше не читаются и не закрываются после claim;
- `Expect` не создается, если public stdout/stderr уже выбрал raw stream mode.

Пользователь отвечает за:

- порядок `send`/`expect`;
- pattern semantics и устойчивость prompt matching;
- выбор transcript/match buffer limits;
- создание `Expect` до первого raw output operation, если prompt automation должна владеть output.

Граница scenario:

- `Expect` не запускает процесс самостоятельно;
- он автоматизирует `Session`, но не добавляет второй process runtime.

## `listen`

Listen-only streaming workflow для `tail`, `logs --follow` и похожих процессов.

Библиотека гарантирует:

- stdout и stderr дренируются параллельно;
- listener callbacks сериализованы для одного stream session;
- медленный listener создает process-pipe backpressure, а не unbounded memory queue;
- retained diagnostics bounded и доступны в `StreamExit`/`StreamException`;
- stdin закрывается на старте по умолчанию;
- `keepStdinOpen()` требует явного `closeStdin()`;
- timeout и listener failure завершают session через общий shutdown path;
- `onExit()` completes после process exit и drain pumps.
- underlying session output ownership принадлежит `StreamSession`, поэтому публичные raw stream wrappers не могут
  конкурировать с streaming pumps.
- `StreamSession` не создается, если public stdout/stderr уже выбрал raw stream mode.

Пользователь отвечает за:

- быструю, bounded работу listener callback;
- перенос тяжелой обработки из callback во внешний bounded executor/queue;
- явное закрытие stdin, если выбран `keepStdinOpen()`.

Граница scenario:

- `listen` не является full-output capture API;
- если нужен completed result с bounded output, используется `run`.

## `lineSession().pooled()` / `protocolSession(factory).pooled()`

Pooled line-oriented или typed protocol workers для CLI/REPL с дорогим startup.

Библиотека гарантирует:

- pool использует существующий `LineSession` или `ProtocolSession` runtime;
- worker lease не раскрывается пользователю;
- `request(...)` сам берет worker, выполняет request и возвращает/retire worker;
- `maxSize` ограничивает live workers;
- `warmupSize` запускает bounded subset заранее;
- `minIdle` и background replenishment держат дорогие workers готовыми без раскрытия leases;
- acquire timeout отличим от request timeout;
- reset/health hooks работают через выбранный session type, имеют typed worker handle и ограничены hook timeout;
- `protocolSession(factory).pooled()` получает serialized factory adapter-ов, чтобы каждый worker владел собственным
  protocol state;
- metrics различают acquire wait, request duration без acquire wait, worker startup duration, failed startup count, live
  states и retire reasons;
- `close()` запрещает новые requests, закрывает idle workers и дает leased workers завершить текущий request.

Пользователь отвечает за:

- idempotent reset hook;
- health check, который не разрушает protocol state;
- выбор pool sizing по startup cost и expected concurrency.

Граница scenario:

- pool не является general process pool;
- он работает только поверх line-oriented или explicit typed protocol scenario.

## Optional integration layer

`CommandBackedTool`, JSON framing helpers и cancellable calls живут в `:icli-integrations`.

Инварианты:

- integration layer не добавляет второй process runtime;
- CLI output считается недоверенным observation data, а не инструкциями;
- structured adapter errors не должны раскрывать raw argv/env или unbounded output;
- JSON parser/writer имеют depth limit, чтобы bounded frame size не превращался в stack-exhaustion risk;
- cancellation мапится в явный outcome и lifecycle close.

## `ScenarioPresets`

Presets — public ergonomics layer для уже выбранного сценария.

Библиотека гарантирует:

- preset является typed `Consumer` существующего scenario builder;
- preset не выбирает сценарий вместо пользователя;
- preset не запускает процесс и не создает runtime resources;
- preset применяет только те overrides, которые уже существуют у соответствующего builder;
- validation параметров preset происходит до применения customizer.

Пользователь отвечает за:

- выбор сценария до применения preset;
- порядок применения preset и явных overrides;
- понимание, что preset не является отдельным execution profile вне resolver/domain layer.

Граница API:

- новый preset допустим только если он сокращает реальный повторяющийся workflow и не добавляет новый runtime path;
- preset не может раскрывать backend-specific или dependency-specific type.

## Release gate

Перед релизом проверяется:

- новые public entry points укладываются в канонический сценарий или optional integration layer;
- новые/измененные public entry points, examples и tests сверены с этим документом;
- dependency-specific types не появились в core public API;
- terminal/PTY детали не вышли за `TerminalPolicy` и provider boundary;
- docs, examples и tests описывают одинаковые guarantees.
