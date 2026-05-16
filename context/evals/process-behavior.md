# Поведенческие проверки исполнения процессов

## Назначение

Этот файл задает минимальный набор оценочных проверок для реализации iCLI. Каждый сценарий должен стать тестом или fixture-case
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
- Очень большие значения `Duration` насыщаются во внутреннем runtime и не превращаются в сырой `ArithmeticException`.
- Ошибка запуска не раскрывает сырые argv-значения в публичном сообщении исключения.
- Невалидные значения окружения отклоняются до запуска и не повторяют сырое значение в сообщении.
- Прерванное ожидание не теряет interrupt status.

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
- `listen` закрывает stdin на старте по умолчанию.
- `keepStdinOpen()` позволяет отложить EOF, а `StreamSession.closeStdin()` завершает stdin без записи input.
- Timeout stream-сценария проходит через общий shutdown path и помечает `StreamExit.timedOut()`.
- Ошибка listener завершает `StreamSession.onExit()` exceptionally с ограниченной диагностикой.
- Диагностический transcript ограничен и не хранит весь output.

## Диагностика

- `run` испускает структурированные lifecycle events: command prepared, process started, process exited.
- `run` испускает метаданные обрезки вывода с источником потока и byte limit.
- `run` испускает timeout и shutdown-request events при timeout.
- `listen` испускает lifecycle, timeout, shutdown и listener-failure events.
- Command echo безопасен для диагностики: сырые значения аргументов и окружения не испускаются; видны executable,
  число аргументов и имена переменных окружения.
- Events одного process lifecycle имеют общий `runId`; разные lifecycles получают разные ids, поэтому параллельные
  запуски можно коррелировать без разбора времени или текста команды.
- Ошибки diagnostic listener и transcript sink, включая non-runtime failures, не меняют поведение команды.
- Доставка diagnostic listener и transcript sink асинхронная best-effort, поэтому медленные diagnostic callbacks не
  блокируют runtime path процесса.
- Test fixtures предоставляют thread-safe diagnostic recorder для assertions.

## Kotlin-эргономика

- Kotlin module компилируется как отдельный optional Gradle project.
- Java core не зависит от Kotlin runtime или coroutines.
- Receiver-style `runCommand` компилируется и запускается.
- Suspending `runCommandAwait`, `Session.awaitExit`, `LineSession.requestAwait` и `StreamSession.awaitExit` работают без
  блокировки caller coroutine.
- Отмена coroutine, ожидающей `Session.awaitExit` или `StreamSession.awaitExit`, не отменяет общий `onExit()` future и не
  обходит session shutdown policy.
- `listenFlow` раскрывает streaming output как `Flow<StreamChunk>` через узкий `ListenFlowInvocation`, который не позволяет
  заменить внутренний listener.
- Медленный `listenFlow` collector не должен молча терять chunks; adapter использует bounded/rendezvous delivery и
  сохраняет backpressure вместо бесконечной очереди.
- Kotlin tests проверяют public extension API на реальных командах.

## Пул line-сессий

- `pooled` открывает workers через существующий `LineSession`, а не через отдельный process runtime.
- `warmupSize` заранее создает workers, а `maxSize` ограничивает общий live worker count.
- Worker переиспользуется между requests, пока не превышены `maxRequestsPerWorker` или `maxWorkerAge`.
- Acquire timeout отличается от request timeout и дает pool-level failure.
- Request timeout/failure retire worker, а не возвращает его в idle set.
- `resetHook` выполняется после успешного user request перед возвратом worker в idle set.
- `healthCheck` выполняется перед lease; unhealthy worker закрывается и заменяется.
- `close()` запрещает новые requests, закрывает idle workers сразу и дает leased workers завершить текущий request.
- `metrics()` возвращает snapshot counters для size, idle, leased, created, retired и request counts.

## Сценарные presets

- Presets компилируются как typed `Consumer` для существующих invocation builders.
- `commandAutomation` задает bounded capture, timeout и separate output для `run`.
- `environmentDiagnostics` задает bounded capture, timeout, UTF-8 и merged output для `run`.
- `binaryOutputCapture` задает bounded capture без per-call text charset override, а `CommandResult` сохраняет byte
  snapshots captured stdout/stderr.
- `replLineMode` задает idle timeout и terminal policy для `lineSession`.
- `promptAutomationSession` задает idle timeout, terminal policy и UTF-8 text-send charset для raw `interactive`.
- `logFollowing` задает absolute stream timeout и close-stdin-on-start для `listen`.
- `terminalRequiredSession` задает terminal-required session preset без отдельного runner.
- `warmWorkerPool` задает max/warmup size и acquire timeout для `pooled`.
- Невалидные preset policies падают до применения к builder.

## CLI-backed integrations

- Optional `:icli-integrations` module компилируется отдельно от core и не добавляет новый process runtime.
- JSON codec round-trips object/array/string/number/boolean/null values и экранирует control characters.
- JSON parser отклоняет trailing content, invalid numbers и raw unescaped control characters.
- JSON Lines helper не допускает raw line separators в frame boundary, но сохраняет escaped embedded line separators.
- `JsonLineSession` отправляет JSON request через existing `LineSession` и получает ровно одну JSON response line.
- Malformed JSON response отличается от command launch failure и мапится в protocol error на adapter layer.
- Content-Length framed JSON helper проверяет missing/invalid headers, oversized body и EOF before body completion.
- `CancellableCall.cancel()` сначала фиксирует cancelled outcome, затем закрывает underlying session/lifecycle owner.
- `ToolCallResult` возвращает structured success/failure, чтобы command-backed tool всегда давал observation.
- `CliAdapterError` не включает raw stdout/stderr excerpts по умолчанию и сохраняет machine-readable code/details.
- Compile-tested examples показывают one-shot command-backed tool, JSONL tool, cancellation и Content-Length framing.

## Performance/stress

- `stressTest` входит в `check` и остается bounded по времени.
- Большой stdout не удерживается целиком при bounded capture и выставляет truncation flag.
- Большой stderr не блокирует завершение процесса и ограничивается независимо от stdout.
- Timeout churn из нескольких параллельных процессов завершается без deadlock и возвращает timeout results.
- Быстрый open/close interactive sessions не оставляет lifecycle futures незавершенными.
- Pooling contention завершает все requests, не превышает `maxSize` и сохраняет metrics accounting.
- PTY stress проверяет несколько terminal-required sessions, если system PTY provider доступен; иначе case skip-ается.
- Memory behavior проверяется через retained-output bounds, а не через нестабильные heap-size assertions.

## Интерактивная session

- Session открывается и закрывается без утечки процесса.
- `sendLine` отправляет строку и flush.
- `closeStdin` корректно сигнализирует EOF.
- `onExit` завершается после выхода процесса.
- Caller-visible idle timeout закрывает зависшую session; активность — успешные записи, закрытие stdin и успешные
  чтения через session streams.
- `close()` и idle timeout проходят через общий shutdown helper; отдельная hardening-проверка escalation path остается
  открытой.
- Ctrl+C/interrupt поведение проверяется через PTY `TerminalSignal.INTERRUPT`.

## Построчный workflow

- Один input дает один response.
- Параллельные вызовы не перемешивают stdin/stdout.
- Response decoder сохраняет значимые переносы строк через `LineResponse.lines()`; `text()` соединяет lines через `\n`.
- Timeout ожидания response дает bounded transcript.
- Timeout закрывает `LineSession`, чтобы следующий request не читал хвосты старого ответа.
- EOF до response отличается от timeout.
- Stderr дренируется в transcript, чтобы line workflow не зависал на заполненном stderr.
- Незавершенный partial output попадает в transcript с корректной привязкой к потоку.

## Expect helper

- Совпадение с literal text.
- Совпадение с regex.
- Timeout содержит transcript.
- EOF до ожидаемого output отличается от timeout.
- Отправка строки после match.
- Порядок send/sendLine виден в transcript.
- ANSI/control-sequence filter может нормализовать вывод перед matching.
- Один `Expect` владеет output streams сессии.
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

Ограничения текущего среза:

- PTY child stdout/stderr под Unix terminal приходят как терминальный поток через `Session.stdout()`;
- Windows ConPTY пока explicit unsupported behavior, а не fallback для `REQUIRED`;
- one-shot `run` пока не получает PTY transport.

## Release gate

Первый release candidate не готов, пока one-shot, streaming, timeout и базовая session группа не покрыты тестами.
