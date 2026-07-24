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
- `SessionExitBarrier` публикует изолированный public `onExit()` только после terminal outcome, физического закрытия
  обоих output streams и завершения зарегистрированных helpers;
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
  `BoundedTaskPermit` владеют capacity, `BoundedTaskOwner` явно выбирает fresh или session-affine execution owner, а
  `BoundedTaskExecution` целиком владеет start gate, abandonment, interrupt и late-failure settlement одного вызова;
  отменяемая и неотменяемая операции передаются ему как явная `BoundedTaskCancellation`, а retry safety line write
  хранит только переход `waiting -> rejected/admitted` без неиспользуемых промежуточных фаз;
- `SessionResources` владеет logical/physical stdin close, сериализацией writes, стабильными ссылками на process streams,
  exclusive output ownership и распределением ответственности за close; factory возвращает полностью связанного
  владельца без промежуточного взаимного bind;
- `SessionOutputCleanup` после физического закрытия обоих output streams отдельно завершает terminal-failure
  settlement и наблюдаемый physical-cleanup future;
- `SessionLateFailures` удерживает physical/late cleanup failures до завершения terminal arbitration, после чего
  один раз прикрепляет их к canonical failure либо отправляет через bounded reporter.
- `ProcessIoResources` транзакционно приобретает стабильные ссылки на process streams и permits; rollback выражен
  только реальными операциями остановки процесса, закрытия полученных streams и возврата capacity;
- `OutputPumpCoordinator` владеет однократным транзакционным запуском пары helper pumps, `OutputPumpCleanup` —
  порядком process cleanup, pump completion, physical close и helper barrier, а `OutputCloseFailures` —
  identity-deduplication и выбором attach/report.
- `DefaultLineSession` координирует один line request, `LineSessionState` сериализует active request, close, EOF и
  terminal/fatal arbitration, а `LineOutputTransport` владеет pumps, line framing и bounded stdout event queue.
- `DefaultProtocolSession` координирует adapter callbacks и protocol I/O, а `ProtocolSessionState` сериализует active
  request, close, EOF, terminal/fatal arbitration и canonical request failures.
- `DefaultExpect` остается пользовательским facade: `ExpectSessionState` сериализует output buffer, match cursor,
  transcript и first-terminal-wins, `ExpectOutputTransport` владеет pumps, decoding и physical cleanup, а
  `ExpectRegexMatcher` изолирует bounded regex execution от session state.

Distinct callbacks отделяют terminal stdin/inline-output failure от фонового physical stdout/stderr close failure.
Оркестратор регистрирует terminal failure до process/resource cleanup и только после cleanup разрешает publication.
Physical output failure входит в physical-cleanup outcome, но не участвует в выборе terminal outcome.

`DefaultSession` определяет порядок этих операций для natural exit, explicit close, idle timeout и failure. Локальные
владельцы не выбирают сценарный terminal outcome друг за друга.

`SessionConstruction` tests инжектируют сбой на реальных асинхронных границах: запуск watcher-а и последний шаг перед
commit. Нижележащая транзакция приобретения process streams принадлежит `ProcessIoResources` и проверяется отдельно;
ее внутренняя форма этим ADR не фиксируется.

Line и protocol session явно откладывают классификацию physical close failures, пока активный request не завершил
terminal arbitration. EOF без активного request закрывает это окно сразу. Поэтому buffered response может успешно
завершиться после EOF, а выбранная активным request ошибка получает уже произошедшие close failures как suppressed до
публикации helper exit. Stdout I/O failure и interrupt во время zero-read backoff сами выбирают terminal failure и не
оставляют окно attribution незавершенным.

## Инварианты

- watcher body не выполняется до успешного construction commit;
- construction failure останавливает процесс, закрывает уже приобретенные stream resources и освобождает publication
  permits;
- terminal outcome и public exit публикуются ровно один раз;
- public exit не опережает физическое закрытие stdout/stderr или helper cleanup;
- canonical terminal failure фиксируется до cleanup и доступен конкурентным failure paths; принявший его cleanup
  завершается до internal terminal publication;
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
- stdout/stderr имеет одного consumer owner, и вместе с ownership передается ответственность за close;
- stdin writes сериализованы; logical close публикуется до асинхронного физического close.
- line-session terminal state не разделен между request coordinator и output transport; transport сообщает причину и
  источник сбоя, но canonical primary failure выбирает только `LineSessionState`.
- protocol-session framing остается у adapter/reader/writer, а active request и canonical terminal outcome выбирает
  только `ProtocolSessionState`; output cleanup может завершиться без terminal attribution лишь после двух успешных
  физических close, когда прикреплять к будущей request failure нечего.
- expect output, cursor и terminal outcome изменяются только через `ExpectSessionState`; transport не выбирает match
  result, а bounded regex evaluator не владеет lifecycle и не выполняется под state lock.

## Следствия

- изменение output ownership не требует рассуждать о terminal winner или process-tree state;
- изменение process cleanup не затрагивает publication barrier;
- `DefaultSession` читается как последовательность lifecycle-операций, а не как несколько вложенных state machines;
- `DefaultLineSession` не содержит внутреннюю реализацию pump threads и terminal state machine;
- `DefaultProtocolSession` не содержит конкурирующие представления active request и terminal state;
- `DefaultExpect` не содержит pump loop, terminal arbitration или bounded-task protocol;
- дополнительные session-level сценарии используют эти же владельцы и не создают второй process runtime.

Новые классы остаются package-private. Public API и observable lifecycle contract не меняются.
