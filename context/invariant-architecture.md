# Архитектура изолированных инвариантов

## Позиция

Широкие возможности Procwright создаются композицией небольших объектов с одним владельцем ответственности, а не
числом public entry points. Пользователь выбирает сценарий; Draft допускает только настройки с определенной семантикой
для этого сценария; runtime получает нормализованный snapshot.

```text
CommandSpec
  -> scenario Draft.with* (immutable snapshot)
  -> execute/open (explicit resource boundary)
  -> internal scenario settings
  -> validated execution/session plan
  -> process/session runtime
  -> typed result or failure
```

Test доказывает инвариант, но не владеет им. Документация объясняет контракт, но не заменяет реализацию.

## Уровни владения

### Command model

`CommandSpec` владеет данными, общими для всех сценариев одной команды: executable, базовыми args, working directory,
environment policy/overrides и shell mode. Он immutable, копирует mutable input и валидирует локальные значения при
каждом `with*`.

Scenario-specific timeout, capture, readiness, terminal, diagnostics, protocol limits и pool policy в `CommandSpec` не
помещаются.

### Scenario Draft

`RunScenario.Draft`, `InteractiveScenario.Draft`, `LineSessionScenario.Draft`,
`ProtocolSessionScenario.Draft` и `StreamScenario.Draft` являются write-only persistent configuration API:

- каждый `with*` возвращает новый Draft;
- исходная ветка остается неизменной и пригодна для повторного/конкурентного terminal call;
- локальная валидация отдельных значений и defensive copying выполняются в `with*`; комбинация настроек и
  нормализованный plan валидируются в `execute()`/`open()` до запуска процесса;
- Draft не вводит callback-based mutable configuration DSL; переданные scenario callbacks могут сохраняться только в
  immutable snapshot, getters не раскрываются;
- terminal method — единственная точка создания процесса или result.

`Expect.Draft` применяет ту же модель к helper, а `LineSessionScenario.PoolDraft` и
`ProtocolSessionScenario.PoolDraft` — к pool lifecycle. Worker settings фиксируются до перехода в `pooled()`.

### Internal settings

Package-private immutable settings разделены по ответственности:

- launch context;
- run input/capture/output;
- session lifecycle и terminal;
- readiness;
- line/protocol limits и decoding;
- diagnostics;
- pool capacity, warmup, hooks и retirement.

Settings не являются public compatibility surface. Они переносят точный snapshot от Draft к runtime и не содержат
открытых ресурсов.

### Plan validation

Локальная проверка одного значения выполняется его policy/value object или `with*`. Проверка комбинации выполняется
при terminal call владельцем соответствующего plan. Например, совместимость capture destination и output mode нельзя
доказать одним setter-ом.

Runtime получает только согласованный plan и не угадывает, какие defaults или overrides имел в виду пользователь.

### Stateful runtime

После запуска владельцем инварианта становится конкретный runtime component:

- process lifecycle — `ProcessLifecycle` и session state owner;
- stdin close — единый bounded close path;
- output ownership — `SessionOutputOwnership`;
- line request serialization — `DefaultLineSession`;
- protocol request write/read — `ProtocolRequestWriter`, `ProtocolResponseReader` и `ProtocolResponseBudget`;
- output backlog — bounded queue владельца сценария;
- pool partition и transitions — `WorkerPoolController`;
- transcript retention — bounded transcript owner;
- diagnostics delivery — diagnostic emitter/dispatcher.

Cleanup phases выполняются независимо, а несколько failures объединяются identity-safe suppression owner. Fallback,
который создает unbounded thread, недопустим.

### Transport

Transport владеет OS-specific launch, pipe/PTY выбором, сигналами и process-tree возможностями. Public API выражает
только `TerminalPolicy`, `TerminalSize`, `TerminalSignal`, `PtyProvider` и `PtyRequest`; platform details не превращаются в
scenario flags.

## Основные инварианты

### API

- `Procwright.command(...)` — единственная фабрика сервиса вокруг команды.
- Пользователь всегда явно выбирает сценарий.
- `execute()`/`open()` не скрыты в configuration callback.
- Public scenario configuration carriers вне Draft отсутствуют.
- Public sealed session handles принадлежат Procwright и не являются SPI.

### Command и security

- executable обязателен, args сохраняют порядок;
- direct argv является default, shell mode включается явно;
- env keys и values валидируются до запуска;
- working directory представлен `Path`;
- failures и diagnostics не раскрывают raw argv/env values;
- CLI output считается недоверенными данными.

### I/O и память

- stdout/stderr дренируются без взаимной блокировки;
- capture, transcript, line/protocol backlog и decoder pending state имеют независимые bounds;
- truncation, malformed decoding и redaction отражаются явно;
- streaming применяет backpressure и не удерживает весь output;
- request/response byte, char и line limits не подменяются transcript limit;
- некорректный или non-progressing codec не может бесконечно удерживать caller или наращивать память.

### Lifecycle

- timeout, explicit close и failure используют общий shutdown policy;
- process-tree cleanup повторно обнаруживает поздних descendants в пределах phase deadline;
- `close()` идемпотентен, а первое terminal outcome не заменяется поздним;
- `onExit()` завершается ровно один раз;
- readiness выполняется после launch, но до возврата handle или перевода worker в idle;
- partial construction failure закрывает все уже созданные ресурсы.

### Concurrency и ownership

- terminal calls одного Draft создают независимые процессы;
- raw `Session` не обещает request serialization;
- `LineSession` и `ProtocolSession` допускают только один request/response cycle одновременно;
- helper или runtime pump получает exclusive ownership stdout/stderr;
- позднее raw чтение после helper claim и поздний helper claim после raw operation отклоняются;
- stream listeners, readiness probes и worker hooks имеют независимые process-wide capacity partitions; зависший
  callback удерживает разрешение только своей категории до фактического возврата;
- readiness, worker hooks и protocol callbacks получают fresh non-inheriting daemon thread на invocation: Java 17 не
  дает поддерживаемого способа очистить произвольный `ThreadLocal`, поэтому callback thread не переходит другому owner;
- тот же conservative fresh-thread контракт намеренно остается для custom charset encoding, provider writes, worker
  startup factories и regex evaluation; их invocation rate ограничен отдельными admissions, а безопасный общий
  lifecycle owner для произвольной реализации не доказан;
- stream listener использует lazy session-affine daemon owner: chunks одной session не создают новые потоки, owner не
  переходит другой session и закрывается после pump completion либо начала остановки; аварийный выход owner либо
  запускает replacement для уже принятой доставки, либо завершает admission ошибкой с точным возвратом разрешения;
- process provider scanner является trusted internal boundary и переиспользует не более 32 non-inheriting daemon
  workers; между operations восстанавливаются name, context class loader, uncaught handler, priority и interrupt status,
  а произвольные `ThreadLocal` запрещены контрактом этой границы;
- admission ограничивает выполняющиеся и abandoned operations; callback queues не растут без границы;
- late `RuntimeException` и `Error` readiness/worker hook после timeout или interruption отправляются ровно один раз
  через bounded failure reporter; ожидаемый `InterruptedException` от отмены отдельно не публикуется;
- late `RuntimeException` и `Error` line/protocol callback публикуются через bounded failure reporter только после
  возврата callback admission; ожидаемый checked interruption scanner operation после owner timeout/interruption не
  публикуется;
- diagnostics сохраняют порядок для одного destination, но отдают dispatcher после bounded batch и продолжают с
  конца общей FIFO-очереди, поэтому непрерывный producer не удерживает dispatcher slots бесконечно;
- interrupt синхронного caller-а восстанавливает interrupt status и не обходит cleanup;
- coroutine cancellation закрывает/retire только session или worker с недостоверным protocol state; ожидание общего
  exit future не получает ownership над процессом.

### Protocol

- `ProtocolAdapter` владеет request framing и определением конца response;
- scenario Draft хранит factory, а `ScenarioRuntime` вызывает ее отдельно перед запуском каждого session/worker;
- concurrent terminal calls могут вызывать factory конкурентно, поэтому factory обязана быть thread-safe;
- adapter создается до process launch; `null` и factory failure не оставляют процесс;
- deadline охватывает validation/encoding, serialized access, write и decode;
- protocol failure закрывает session, потому что дальнейшее framing state неизвестно;
- stable reason enum отделяет timeout, EOF, broken pipe, decode, oversize, backlog и adapter failure.

### Pool

- pool использует существующий line/protocol runtime и не раскрывает lease;
- каждый worker всегда принадлежит ровно одному состоянию: starting, idle, leased или retiring;
- `maxSize` ограничивает live slots, включая starting/retiring;
- acquire timeout и request timeout различаются;
- failed request/timeout/decoder/process exit retire worker;
- reset/health hooks bounded и не выполняются одновременно с пользовательским request;
- close запрещает новые requests, закрывает idle workers и дает активным requests завершить установленный lifecycle;
- metrics являются снимком наблюдаемого состояния, а retirement reason не выводится из текста exception.

### Integrations и Kotlin

- optional modules не создают второй process runtime;
- Kotlin extensions работают с теми же Java Draft и handles;
- `openFlow()` cold: каждая collection открывает и закрывает собственную `StreamSession`;
- `protocolAdapterFactory { ... }` создает отдельный adapter wrapper на каждый factory call;
- JSON/framing helpers валидируют границы до domain parsing и не публикуют unbounded raw output в errors.

## Анти-паттерны

Недопустимы:

- boolean soup вместо policy/value object;
- второй публичный dialect через дополнительные configuration carriers;
- instance adapter, разделяемый несколькими protocol sessions;
- validation, размазанная по launcher и runtime;
- getter-rich Draft, превращающийся в domain model;
- hidden process spawn в `with*`, preset или Kotlin configuration block;
- unbounded queue/executor как fallback cleanup;
- новый scenario name без отдельного lifecycle или invariant set;
- тест, который закрепляет внутреннюю структуру вместо public behavior.

## Проверка

Для каждого инварианта в [quality/invariant-proof-map.md](quality/invariant-proof-map.md) должны быть указаны владелец и
proof. Public surface проверяется exact signature baseline и compilation внешних consumers. Stateful инварианты
проверяются unit, integration и bounded stress tests; platform capability — отдельной CI matrix.

Архитектурное изменение принимается только после ответа на три вопроса:

1. Какой пользовательский сценарий стало возможно выразить?
2. Какой компонент единолично владеет новым правилом?
3. Какая проверка отличает правильное поведение от похожего, но ошибочного?
