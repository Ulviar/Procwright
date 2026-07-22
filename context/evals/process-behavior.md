# Поведенческие проверки исполнения процессов

## Назначение

Этот файл задает минимальный набор оценочных проверок для реализации Procwright. Каждый сценарий должен стать тестом или fixture-case
до того, как соответствующее поведение будет считаться готовым.

## Одноразовые команды

- Команда завершается с кодом выхода `0`, stdout захвачен полностью.
- Команда завершается с ненулевым кодом выхода, stdout/stderr доступны для диагностики.
- Большой stdout обрезается по ограничивающей политике и выставляет флаг обрезки.
- Большой stderr обрезается независимо от stdout.
- Stdout/stderr можно объединить в один поток.
- Команда получает рабочую директорию.
- Команда получает переопределения окружения.
- Unicode-вывод декодируется заданным charset.
- Timeout приводит к shutdown-политике и понятному результату или исключению.
- Timeout при заблокированной записи stdin завершается bounded cleanup, а не оставляет lifecycle task без ожидания.
- Graceful и forceful shutdown повторно обнаруживают descendants до своего deadline, включая child, созданный root
  process уже во время graceful termination.
- `SecurityException`/unsupported access при перечислении descendants или проверке liveness не отменяет попытку
  остановить root process и не пропускает failure cleanup.
- Вызовы `Process.destroy()`/`destroyForcibly()`, которые сами зависают, выполняются через общую bounded capacity.
  Исчерпание capacity дает typed failure без создания fallback threads; немедленный `Error` возвращается caller-у, а
  поздний failure после окончания окна наблюдения передается uncaught-exception handler.
- Очень большие значения `Duration` насыщаются во внутреннем runtime и не превращаются в сырой `ArithmeticException`.
- Ошибка запуска не раскрывает сырые argv-значения в публичном сообщении исключения.
- Невалидные значения окружения отклоняются до запуска и не повторяют сырое значение в сообщении.
- Прерванное ожидание не теряет interrupt status. Доказано:
  `OneShotExecutionIntegrationTest.callerInterruptDuringRunIsTypedFailureAndRestoresInterruptStatus` и
  `LineSessionIntegrationTest.callerInterruptDuringRequestIsTypedFailureAndRestoresInterruptStatus`.

## Shell и argv

- По умолчанию команда запускается direct argv без shell.
- Shell mode включается явно.
- Аргументы с пробелами не ломают direct argv запуск.
- Shell-specific поведение не появляется случайно в direct mode.

## Стриминг

- Stdout и stderr дренируются параллельно.
- Процесс, который пишет много в stderr и мало в stdout, не зависает.
- Streaming-listener получает chunks до завершения процесса.
- Медленный listener не должен бесконечно раздувать память; текущий `listen` вызывает listener синхронно и
  сериализованно, тем самым применяя backpressure к pipe.
- `listen` всегда закрывает stdin на старте; для caller-owned stdin используется `interactive()`.
- Timeout stream-сценария проходит через общий shutdown path и помечает `StreamExit.timedOut()`.
- Любой failure listener, включая `Error`, завершает `StreamSession.onExit()` exceptionally с ограниченной диагностикой.
- `StreamException.Reason` различает `LISTENER_FAILED`, `OUTPUT_READ_FAILED` и `PROCESS_FAILED`; integration adapter
  маппит эти причины без string matching.
- Failure при создании stream handle после запуска процесса закрывает уже созданную session; ошибка cleanup не заменяет
  исходную, а добавляется как suppressed.
- Диагностический transcript ограничен и не хранит весь output.

## Диагностика

- `run` испускает структурированные lifecycle events: command prepared, process started, process exited.
- `run` испускает метаданные обрезки вывода с источником потока и byte limit.
- `run` испускает timeout и shutdown-request events при timeout.
- `listen` испускает lifecycle, timeout, shutdown, listener-failure и `PROCESS_FAILED` events на failure path.
- Command echo безопасен для диагностики: сырые значения аргументов и окружения не испускаются; видны executable,
  число аргументов и имена переменных окружения.
- Events одного process lifecycle имеют общий `runId`; разные lifecycles получают разные ids, поэтому параллельные
  запуски можно коррелировать без разбора времени или текста команды.
- Ошибки diagnostic listener и transcript sink, включая non-runtime failures, не меняют поведение команды.
- Доставка diagnostic listener и transcript sink асинхронная best-effort, поэтому медленные diagnostic callbacks не
  блокируют runtime path процесса.
- Test fixtures предоставляют thread-safe diagnostic recorder для assertions.

## Cookbook и examples

- `context/scenario-cookbook.md` описывает выбор сценария через пользовательские workflows, а не низкоуровневые knobs.
- Канонические Java examples компилируются и выполняются как внешний consumer; Kotlin и JSON Lines entry points также
  запускаются отдельными consumer gates.
- Shared worker/adapter sources перечислены в `docs/examples.md`; страница с прямой ссылкой на canonical entry point
  должна рядом вести к соответствующему разделу этого manifest.
- Java, Kotlin и JSON Lines workers явно используют UTF-8. Length framing считает bytes и читает exact byte count;
  line/protocol/pooled/Kotlin/JSON paths выполняют non-ASCII round-trip.
- Новый cookbook recipe добавляется только вместе с executable example или явным release limitation.

## Kotlin-эргономика

- Kotlin module компилируется как отдельный optional Gradle project.
- Java core не зависит от Kotlin runtime или coroutines.
- Kotlin duration extensions возвращают новые Java Draft и не меняют исходную ветку.
- Suspending `run().executeAwait()`, `Session.awaitExit`, `LineSession.requestAwait` и `StreamSession.awaitExit` работают
  без блокировки caller coroutine.
- Отмена coroutine, ожидающей `Session.awaitExit` или `StreamSession.awaitExit`, не отменяет общий `onExit()` future и не
  обходит session shutdown policy.
- `listen().openFlow()` возвращает cold `Flow<StreamChunk>`; каждая collection открывает независимую session, а
  cancellation закрывает только принадлежащую collector-у session.
- Медленный `openFlow()` collector не должен молча терять chunks; adapter использует bounded/rendezvous delivery и
  сохраняет backpressure вместо бесконечной очереди.
- `protocolAdapterFactory { ... }` выполняет configuration block на каждый factory call и изолирует adapter state
  между sessions/workers.
- Kotlin tests проверяют public extension API на реальных командах.

## Пул line-сессий

- `lineSession().pooled()` открывает workers через существующий `LineSession`, а не через отдельный process runtime.
- `warmupSize` заранее создает workers, а `maxSize` ограничивает общий live worker count.
- Если создание pool падает после частичного warmup или при запуске replenishment, уже созданные workers закрываются.
- Worker переиспользуется между requests, пока не превышены `maxRequestsPerWorker` или `maxWorkerAge`.
- Acquire timeout отличается от request timeout и дает pool-level failure.
- Кодирование pooled request использует request deadline, но не смешивает его с отдельным acquire timeout.
- Request timeout/failure retire worker, а не возвращает его в idle set.
- `resetHook` выполняется после успешного user request перед возвратом worker в idle set.
- `Error` из user request сохраняется без оборачивания и учитывается как failed request. `Error` из `resetHook` также
  сохраняется, но уже полученный response учитывается как completed request, а worker retires с `RESET_FAILED`.
- `healthCheck` выполняется перед lease; unhealthy worker закрывается и заменяется.
- `close()` запрещает новые requests, закрывает idle workers сразу и дает leased workers завершить текущий request.
- `metrics()` возвращает snapshot counters для size, idle, leased, created, retired и request counts.

## Protocol integrations

- Optional `:procwright-integrations` module компилируется отдельно от core и не добавляет новый process runtime.
- JSON adapters принимают и возвращают Jackson `JsonNode`; собственной JSON-модели и parser framework нет.
- JSON Lines adapter сохраняет escaped line separators и отклоняет malformed или trailing JSON.
- Content-Length adapter проверяет header grammar и body limit до чтения body, затем применяет strict UTF-8 и Jackson.
- Delimiter adapter отклоняет request, содержащий delimiter, до записи части frame.
- Каждая factory создает отдельный adapter; `typedJsonSession` сохраняет это свойство для direct и pooled sessions.
- Compile-tested examples выполняют JSON Lines, delimiter и typed Content-Length round-trips.

## Performance/stress

- `stressTest` входит в `check` и остается bounded по времени.
- Большой stdout не удерживается целиком при bounded capture и выставляет truncation flag.
- Большой stderr не блокирует завершение процесса и ограничивается независимо от stdout.
- Protocol readers должны доказывать response byte/char limits на delimiter/text framing без удержания полного ответа.
- Transcript/diagnostics retention проверяется через длину сохраненного snapshot, а не через текущий размер heap.
- Timeout churn из нескольких параллельных процессов завершается без deadlock и возвращает timeout results.
- One-shot cleanup после timeout имеет bounded wait для stdin/stdout/stderr lifecycle tasks.
- Быстрый open/close interactive sessions не оставляет lifecycle futures незавершенными.
- Pooling contention завершает все requests, не превышает `maxSize` и сохраняет metrics accounting.
- PTY stress проверяет несколько terminal-required sessions, если system PTY provider доступен; иначе case skip-ается.
- Memory behavior проверяется через retained-output bounds, а не через нестабильные heap-size assertions.

## Интерактивная session

- Session открывается и закрывается без утечки процесса.
- `sendLine` отправляет строку и flush.
- `closeStdin` корректно сигнализирует EOF.
- `closeStdin` и общий session cleanup не ждут синхронно потенциально заблокированный `OutputStream.close()`; закрытие
  выполняется через ограниченную library-managed capacity.
- Все raw-stdin close paths используют одну bounded capacity. Если она исчерпана, `closeStdin()` возвращает typed
  runtime failure и закрывает session; unbounded fallback thread не создается.
- `onExit` завершается после выхода процесса.
- Caller-visible idle timeout закрывает зависшую session; активность — успешные записи, закрытие stdin и успешные
  чтения через session streams.
- После передачи output ownership higher-level helper публичные stdout/stderr wrappers не читают и не закрывают process
  output streams.
- Первая публичная operation над stdout/stderr выбирает raw stream mode; поздний helper claim fail fast, включая
  in-flight raw read, `mark` и `reset`.
- `close()` и idle timeout проходят через общий shutdown helper (`ProcessLifecycle.stop`); escalation branch этого
  helper (процесс игнорирует interrupt signal и принудительно убивается после interrupt grace) доказан тестом
  `OneShotExecutionIntegrationTest.shutdownEscalationForceKillsProcessThatSurvivesInterruptSignal` (POSIX).
- Ctrl+C/interrupt поведение проверяется через PTY `TerminalSignal.INTERRUPT`.

## Построчный workflow

- Один input дает один response.
- Параллельные вызовы не перемешивают stdin/stdout.
- Validation, charset encoding, write и response decoding входят в один request deadline; пользовательский
  `CharsetEncoder`, который игнорирует interrupt, не может удерживать caller thread за пределами deadline.
- Если bounded readiness/worker-hook callback завершается с `RuntimeException` или `Error` уже после timeout или
  interruption caller-а, ошибка не теряется: она ровно один раз передается через bounded failure reporter после
  перехода task в abandoned state. Ожидаемый `InterruptedException` от отмены не считается поздней runtime-ошибкой.
- Incremental stdout/stderr decoder ограничивает retained undecoded bytes и output без input consumption; decoder,
  который сообщает overflow без progress или перемещает input position назад, закрывает session с `DECODE_ERROR`.
- При `CodingErrorAction.REPLACE` decoder не может опубликовать replacement, если заявленная error length не помещается
  в оставшийся input; нарушение также становится `DECODE_ERROR`.
- Response decoder сохраняет значимые переносы строк через `LineResponse.lines()`; `text()` соединяет lines через `\n`.
- Timeout ожидания response дает bounded transcript.
- Timeout закрывает `LineSession`, чтобы следующий request не читал хвосты старого ответа.
- EOF до response отличается от timeout.
- Stderr дренируется в transcript, чтобы line workflow не зависал на заполненном stderr.
- Незавершенный partial output попадает в transcript с корректной привязкой к потоку.
- `maxLineChars` применяется повторно к последней незавершенной строке при EOF, включая строку с одиноким завершающим
  `\r`.
- `LineSession` claims output ownership, и публичные raw stdout/stderr operations underlying session fail fast.
- `LineSession` не создается после уже начатой или завершенной raw stdout/stderr operation.

## Протокольный workflow

- Persistent text decoder сохраняет состояние между request-scoped reads, но pending undecoded bytes и output без
  input consumption ограничены независимо от response/transcript retention.
- Decoder rewind, отсутствие progress и некорректная replacement error length дают `DECODE_ERROR`, bounded transcript,
  закрывают process и сохраняют ту же terminal reason для следующего request.
- Protocol request timeout после adapter admission дает `TIMEOUT`; `onExit()` завершается после cleanup, а следующий
  request возвращает сохраненный `TIMEOUT`, а не generic `CLOSED`. Timeout/interrupt во время ожидания serialized slot
  не допускает adapter к stdin, оставляет session открытой и не уступает уже выбранному terminal/fatal outcome.
- `ResponseDecoder.Reader`, `ProtocolWriter` и `ProtocolReader` действуют только в одном callback invocation и на его
  thread; retained или cross-thread capability не может затронуть I/O текущего или следующего request.
- UTF-8 framed examples считают длину тела в bytes и читают exact byte count, поэтому non-ASCII payload не нарушает
  framing.

## Expect helper

- Совпадение с literal text.
- Совпадение с regex.
- Timeout содержит transcript.
- EOF до ожидаемого output отличается от timeout.
- Отправка строки после match.
- Порядок send/sendLine виден в transcript.
- Встроенное incremental CSI stripping удаляет полные 7-bit ECMA-48 CSI sequences с префиксом `ESC [` перед matching и
  transcript retention, сохраняет incomplete/malformed/overlong candidates и ведет независимое bounded state для
  stdout/stderr.
- Один `Expect` владеет output streams сессии.
- Создание `Expect.Draft` и получение raw stdout/stderr wrappers не выбирают ownership; его выбирает `open()` либо первая
  фактическая raw operation.
- Raw stdout/stderr wrappers, полученные до или после создания `Expect`, fail fast после output ownership claim.
- `Expect` не создается после уже начатой или завершенной raw stdout/stderr operation.
- Закрытие `Expect` закрывает underlying `Session` и не возвращает streams raw caller.
- Match buffer ограничен и не растет бесконечно.

## PTY

Базовый PTY transport реализован для session-сценариев. Минимальные проверки:

- command может запросить terminal через `TerminalPolicy`;
- `REQUIRED` не делает silent fallback и падает с явной ошибкой, если provider unavailable;
- `AUTO` может fallback в pipes, если provider unavailable;
- PTY session может отправлять и получать text;
- `lineSession` может работать под PTY с echo-aware decoder;
- real shell проходит под Unix `script(1)` provider;
- terminal size передается в `PtyRequest` и системный provider выставляет `LINES`/`COLUMNS` плюс делает best-effort `stty`;
- `Session.sendSignal(TerminalSignal.INTERRUPT)` проверен как Ctrl+C-style mapping под PTY.
- Linux/JDK 17 CI job требует доступный system PTY и фактическое выполнение PTY tests; остальные платформы могут
  использовать assumptions для unsupported capability.

Ограничения текущего среза:

- PTY child stdout/stderr под Unix terminal приходят как терминальный поток через `Session.stdout()`;
- Windows ConPTY пока explicit unsupported behavior, а не fallback для `REQUIRED`;
- one-shot `run` пока не получает PTY transport.

## Publication readiness

Первый публичный релиз не готов, пока выполнены не все условия:

- one-shot, streaming, timeout и базовая session группа покрыты тестами;
- scenario cookbook сверяется с compile-tested examples;
- Kotlin и integrations modules проходят свои tests;
- bounded `stressTest` входит в `check` и проходит локально;
- `quickCheck`, `scenarioCheck`, `regressionCheck` и `publicationReadinessCheck` соответствуют
  [test-tiers.md](test-tiers.md);
- `javadoc` проходит для Java modules;
- `:procwright-kotlin:javadocJar` запускает Dokka-проверку с
  `reportUndocumented=true` и `failOnWarning=true`;
- Kotlin binary API проходит встроенную Kotlin Gradle Plugin ABI validation относительно tracked baseline;
- public package boundaries покрыты tests;
- LICENSE присутствует в корне репозитория;
- versioning policy, compatibility policy, dependency review и publication readiness актуальны;
- session shutdown escalation hardening закрыт тестом
  `OneShotExecutionIntegrationTest.shutdownEscalationForceKillsProcessThatSurvivesInterruptSignal` через общий
  shutdown helper `ProcessLifecycle.stop`;
- Java 17-targeted build проходит scenario checks на Linux, macOS и Windows с JDK 17, а также на Linux с JDK 21/25;
  source targets 21/25 отдельно проходят scenario checks на соответствующих Linux/JDK.
