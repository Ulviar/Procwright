# ADR-0024: Когнитивная архитектура sessions

## Статус

Принято.

## Контекст

Raw session одновременно координирует запуск наблюдателей, stdin/stdout/stderr, process tree, terminal outcome и
публикацию `onExit()`. Эти обязанности связаны общим lifecycle, но подчиняются разным инвариантам. Один общий state
owner вынуждает при локальном изменении держать в голове весь протокол завершения процесса и потоков.

Механическое дробление по методам не решает проблему: полезная граница должна давать компоненту собственное состояние,
одно правило завершения и проверяемый контракт.

## Решение

`DefaultSession` остается оркестратором raw-session сценария и делегирует локальные автоматы следующим владельцам:

- `SessionConstruction` удерживает приобретенные ресурсы до единственного решения commit/rollback и не пропускает
  watcher-ы через construction gate до commit;
- `SessionTermination` владеет session state, выбором terminal outcome и внутренней terminal publication; принятый
  terminal failure регистрирует незавершенный cleanup и удерживает publication до его явного завершения;
- `SessionExitBarrier` хранит process/output dependencies как явные pending/settled states и публикует изолированный
  public `onExit()` неизменяемым action только после terminal outcome, физического закрытия обоих output streams и
  завершения зарегистрированных helpers;
- `SessionProcessCleanup` ожидает natural exit, хранит exit code natural/explicit stop и snapshot живых descendants, а также
  сериализует exact-once остановку process tree;
- `ProcessTreeScanner` владеет bounded traversal и guarded views для объектов `Process`/`ProcessHandle`, полученных от
  provider-а;
- `ProcessProviderOperationOwner` принимает не более 32 provider operations одновременно и для каждого accepted вызова
  создает fresh disposable non-inheriting daemon owner; admission удерживается до фактического возврата operation, в том
  числе после caller timeout или interruption;
- `ProcessProviderOperationCancellation` доставляет interrupt до или после binding owner-а, а
  `ProcessProviderOperationSettlement` удерживает reporting producer до завершения caller и owner;
- `BoundedTaskLimits` задает независимые process-wide admission partitions, `BoundedTaskLimiter` и
  `BoundedTaskPermit` владеют capacity, `BoundedTaskRunner.TaskStarter` запускает adaptive или session-affine execution
  owner, а `BoundedTaskHandoff` одним monitor владеет claim callback, admission и наблюдаемой retry-safe фазой;
  `BoundedTaskExecution` владеет abandonment, interrupt и late-failure settlement одного принятого вызова. Отменяемая и
  неотменяемая операции передаются ему как явная `BoundedTaskCancellation`;
- `SessionResources` владеет logical stdin close, сериализацией writes, exclusive output ownership и распределением
  session-level close callbacks; factory возвращает полностью связанного владельца без промежуточного взаимного bind;
- `SessionOutputCleanup` после физического закрытия обоих output streams завершает один immutable outcome. Sealed
  physical-close variant явно различает success, helper-owned failure и lifecycle failure; наблюдаемый
  physical-cleanup future является производным view этого же outcome. `SessionExitBarrier` получает inline failures и
  только lifecycle-owned physical failure; helper-owned failure остается у helper-а;
- `SessionExitBarrier` получает immutable source-failure snapshot из `SessionTermination` и один раз объединяет process
  и raw-output failures без изменения исходных `Throwable`; lone physical failure успешного raw process уходит в
  bounded reporter, а helper-owned failure остается у helper-а. Отдельный late-failure arbiter не нужен.
- `ProcessIoAcquisition` транзакционно приобретает стабильные ссылки на process streams и permits; при отказе сначала
  выполняет все обязательные rollback-операции, и только затем дополняет primary failure;
- `ProcessStreamResource` владеет exact-once close одного stream, его permits и локальным close failure; общий для трех
  ресурсов lock отвечает только за атомарный single/pair claim;
- `ProcessIoResources` группирует три ресурса и координирует bundle-level close и rollback;
- `OutputPumpCoordinator` владеет однократным транзакционным запуском пары helper pumps, `OutputPumpCleanup` —
  порядком process cleanup, pump completion и physical close. Один completion future публикует helper barrier и
  зависимые действия после состояния `FINISHED`; отдельные списки callbacks и дублирующие completion-флаги не нужны.
  `OutputCloseFailures` владеет identity-deduplication, выбором уже представленной terminal failure и bounded
  best-effort reporting остальных.
- `DefaultLineSession` координирует один line request, `LineSessionState` сериализует active request, close, EOF и
  terminal/fatal arbitration и публикует только типобезопасные failure/fatal snapshots. Возвращаемый state-ом
  `AutoCloseable` request scope единолично освобождает active request и exact-once закрывает attribution после EOF.
  `LineRequestWriter` владеет bounded stdin write и retry-safe handoff, `LineResponseDecoder` — bounded decoder callback,
  capability lifetime и response limits, а `LineOutputTransport` — pumps, line framing и bounded stdout event queue.
- `DefaultProtocolSession` координирует adapter callbacks и request phases. `ProtocolOutputTransport` владеет pumps,
  transcript, асимметричными stdout/stderr queues и созданием request-scoped readers, а `ProtocolSessionState`
  сериализует active request, close, EOF, terminal/fatal arbitration и canonical request failures. Request scope,
  failure/fatal/closed snapshots и close claim представлены отдельными sealed/typed variants без nullable payload.
  Terminal snapshot удерживает готовый transcript и доступный при выборе failure exit code. Более позднее наблюдение
  process exit обогащает только exit metadata до публикации `onExit`; state monitor при этом не вызывает transcript
  или process supplier и не входит в monitor decoder-а.
- `DefaultExpect` остается пользовательским facade: `ExpectSessionState` сериализует output buffer, match cursor,
  transcript и first-terminal-wins, `ExpectOutputTransport` владеет pumps, decoding и physical cleanup, а
  `ExpectRegexMatcher` изолирует bounded regex execution от session state.
- `DefaultStreamSession` координирует владельцев streaming lifecycle. `StreamOutputReader` выполняет один
  incremental read/decode loop, `StreamListenerDispatcher` сериализует listener callbacks и владеет их bounded
  execution, `StreamTimeoutWatcher` — запуском и полной остановкой timeout owner-а, включая возврат expiration callback,
  а `StreamSessionState` одним monitor сериализует terminal outcome, nested raw-session terminal, завершение pumps и
  exact-once claim публикации.

Distinct callbacks отделяют terminal stdin/inline-output failure от фонового physical stdout/stderr close failure.
Оркестратор регистрирует terminal failure до process/resource cleanup и только после cleanup разрешает publication.
Physical output failure входит в physical-cleanup outcome, но не участвует в выборе terminal outcome.

`DefaultSession` определяет порядок этих операций для natural exit, explicit close, idle timeout и failure. Локальные
владельцы не выбирают сценарный terminal outcome друг за друга.

`SessionConstruction` tests инжектируют сбой на реальных асинхронных границах: запуск watcher-а и последний шаг перед
commit. Нижележащая транзакция приобретения process streams принадлежит `ProcessIoAcquisition` и проверяется отдельно;
ее внутренняя форма этим ADR не фиксируется.

Line и protocol session явно откладывают классификацию physical close failures, пока активный request не завершил
terminal arbitration. EOF без активного request закрывает это окно сразу. Поэтому buffered response может успешно
завершиться после EOF, а уже произошедшие close failures отправляются отдельными bounded reports только после выбора
terminal outcome. Они не изменяют выбранную request failure и не задерживают публикацию helper exit. Stdout I/O failure
и interrupt во время zero-read backoff сами выбирают terminal failure и не оставляют окно attribution незавершенным.

## Инварианты

- watcher body не выполняется до успешного construction commit;
- construction failure останавливает процесс, закрывает уже приобретенные stream resources и освобождает publication
  permits;
- terminal outcome и public exit публикуются ровно один раз;
- public exit не опережает физическое закрытие stdout/stderr или helper cleanup;
- canonical terminal failure фиксируется до cleanup и доступен конкурентным failure paths; принявший его cleanup
  завершается до internal terminal publication;
- session state monitor не выполняет transcript snapshot, user callback или другой внешний component operation;
- обязательный lifecycle outcome хранит failures как identity-дедуплицированные данные и не зависит от мутации
  исходных `Throwable`; проигравшая необязательная диагностика отправляется отдельным bounded best-effort report и не
  задерживает request, helper или terminal publication;
- process terminal failure и независимо завершившийся inline-output terminal failure образуют новый стабильный
  aggregate без изменения исходных `Throwable`; physical output close остается отдельным non-terminal barrier result;
- asynchronous physical stdout/stderr close failure не заменяет natural/explicit success;
- failure запуска best-effort close notification не выходит в mandatory fallback close loop и не задерживает уже
  принятое следующее физическое закрытие;
- physical close failure line/protocol helper не отправляется как бесхозная late failure, пока активный request еще
  может выбрать canonical terminal failure;
- process tree останавливается не более одного раза; успешный exit-code snapshot переиспользуется, а canonical failure
  хранится в `SessionTermination` и наблюдается через `onExit()`;
- accepted provider operation выполняется на отдельном non-inheriting daemon owner-е и удерживает один из 32 permits до
  фактического возврата; abandoned operation не освобождает permit преждевременно;
- provider owners не переиспользуются и не требуют неполной санации `ThreadLocal` или mutable thread state;
- helper terminal publication композируется с physical-output settlement callback и не использует polling;
- bounded callback admission и retry-safe handoff принадлежат одному `BoundedTaskHandoff`; параллельные flags не могут
  представить несовместимые фазы одного callback;
- stdout/stderr имеет одного consumer owner, и вместе с ownership передается ответственность за close;
- stdin writes сериализованы; logical close публикуется до асинхронного физического close.
- line-session terminal state не разделен между request coordinator и output transport; transport сообщает причину и
  источник сбоя, но canonical primary failure и завершение active request выбирает только `LineSessionState`.
- protocol-session framing остается у adapter/reader/writer, а active request и canonical terminal outcome выбирает
  только `ProtocolSessionState`; `ProtocolOutputTransport` не интерпретирует framing. Output cleanup может завершиться
  без terminal attribution лишь после двух успешных физических close, когда относительно будущей request failure
  больше нечего классифицировать.
- expect output, cursor и terminal outcome изменяются только через `ExpectSessionState`; transport не выбирает match
  result, а bounded regex evaluator не владеет lifecycle и не выполняется под state lock.
- stream terminal outcome не распределен между независимыми atomics: первый control/failure winner, проигравшие
  failures, nested raw-session terminal и готовность обоих output pumps согласует только `StreamSessionState`;
  failure completion ждет pumps, но не требует nested exit после запуска cleanup.
- timeout, output read/decode и listener callback streaming-сценария имеют отдельных владельцев; ни один из них не
  выбирает terminal outcome самостоятельно.

## Следствия

- изменение output ownership не требует рассуждать о terminal winner или process-tree state;
- изменение process cleanup не затрагивает publication barrier;
- `DefaultSession` читается как последовательность lifecycle-операций, а не как несколько вложенных state machines;
- `DefaultLineSession` не содержит внутреннюю реализацию pump threads и terminal state machine;
- `DefaultLineSession` не содержит реализацию bounded write или decoder callback;
- `DefaultProtocolSession` не содержит pump loops, output queues или конкурирующие представления active request и
  terminal state;
- `DefaultExpect` не содержит pump loop, terminal arbitration или bounded-task protocol;
- `DefaultStreamSession` не содержит read/decode loop, callback-admission protocol, timeout thread lifecycle,
  собственное семейство terminal outcome типов или publication races;
- дополнительные session-level сценарии используют эти же владельцы и не создают второй process runtime.

Компоненты декомпозиции находятся в неэкспортируемых internal-пакетах и не входят в поддерживаемый public API.
`ProcessStreamResource` имеет `public` visibility только для доступа из подпакета `internal.session`. Public types и
порядок lifecycle barrier сохраняют прежний observable contract. Diagnostics при двух независимых failures образуют
стабильный aggregate, построенный один раз из исходного failure snapshot и описанный в `Session.onExit()`.
