# Поведенческие проверки process execution

## Назначение

Этот файл задает минимальный eval-набор для реализации iCLI. Каждый сценарий должен стать тестом или fixture-case
до того, как соответствующее поведение будет считаться готовым.

## One-shot команды

- Команда завершается с exit code `0`, stdout захвачен полностью.
- Команда завершается с non-zero exit code, stdout/stderr доступны для диагностики.
- Большой stdout обрезается по bounded policy и выставляет truncation flag.
- Большой stderr обрезается независимо от stdout.
- Stdout/stderr можно объединить в один поток.
- Команда получает working directory.
- Команда получает environment override.
- Unicode output декодируется заданным charset.
- Timeout приводит к shutdown policy и понятному результату или исключению.
- Interrupted wait не теряет interrupt status.

## Shell и argv

- По умолчанию команда запускается direct argv без shell.
- Shell mode включается явно.
- Аргументы с пробелами не ломают direct argv запуск.
- Shell-specific поведение не появляется случайно в direct mode.

## Streaming

- Stdout и stderr дренируются параллельно.
- Процесс, который пишет много в stderr и мало в stdout, не зависает.
- Streaming listener получает chunks до завершения процесса.
- Медленный listener не должен бесконечно раздувать память; текущий `listen` вызывает listener синхронно и
  сериализованно, тем самым применяя backpressure к pipe.
- `listen` закрывает stdin на старте по умолчанию.
- `keepStdinOpen()` позволяет отложить EOF, а `StreamSession.closeStdin()` завершает stdin без записи input.
- Timeout stream-сценария проходит через общий shutdown path и помечает `StreamExit.timedOut()`.
- Listener failure завершает `StreamSession.onExit()` exceptionally с bounded diagnostics.
- Diagnostic transcript bounded и не хранит весь output.

## Diagnostics

- `run` emits structured lifecycle events: command prepared, process started, process exited.
- `run` emits output truncation metadata with stream source and byte limit.
- `run` emits timeout and shutdown-request events on timeout.
- `listen` emits lifecycle, timeout, shutdown and listener-failure events.
- Command echo is redaction-friendly: raw argument values and environment values are not emitted; executable, argument
  count and environment names are listed.
- Diagnostic listener and transcript sink failures, including non-runtime failures, do not change command behavior.
- Diagnostic listener and transcript sink delivery is best-effort asynchronous, so slow diagnostics callbacks do not
  block the process runtime path.
- Test fixtures provide a thread-safe diagnostic recorder for assertions.

## Kotlin ergonomics

- Kotlin module compiles as a separate optional Gradle project.
- Java core does not depend on Kotlin runtime or coroutines.
- Receiver-style `runCommand` compiles and runs.
- Suspending `runCommandAwait`, `Session.awaitExit`, `LineSession.requestAwait` and `StreamSession.awaitExit` work without
  blocking the caller coroutine.
- Отмена coroutine, ожидающей `Session.awaitExit` или `StreamSession.awaitExit`, не отменяет общий `onExit()` future и не
  обходит session shutdown policy.
- `listenFlow` exposes streaming output as `Flow<StreamChunk>` через узкий `ListenFlowInvocation`, который не позволяет
  заменить внутренний listener.
- Медленный `listenFlow` collector не должен молча терять chunks; adapter использует bounded/rendezvous delivery и
  сохраняет backpressure вместо бесконечной очереди.
- Kotlin tests exercise the public extension API against real commands.

## Interactive session

- Session открывается и закрывается без утечки процесса.
- `sendLine` отправляет строку и flush.
- `closeStdin` корректно сигнализирует EOF.
- `onExit` завершается после выхода процесса.
- Caller-visible idle timeout закрывает зависшую session; активность — успешные записи, закрытие stdin и успешные
  чтения через session streams.
- `close()` и idle timeout проходят через общий shutdown helper; отдельная hardening-проверка escalation path остается
  открытой.
- Ctrl+C/interrupt поведение проверяется через PTY `TerminalSignal.INTERRUPT`.

## Line-oriented workflow

- Один input дает один response.
- Параллельные вызовы не перемешивают stdin/stdout.
- Response decoder сохраняет значимые переносы строк через `LineResponse.lines()`; `text()` соединяет lines через `\n`.
- Timeout ожидания response дает bounded transcript.
- Timeout закрывает `LineSession`, чтобы следующий request не читал хвосты старого ответа.
- EOF before response отличается от timeout.
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

- command can request terminal through `TerminalPolicy`;
- `REQUIRED` не делает silent fallback и падает с явной ошибкой, если provider unavailable;
- `AUTO` может fallback в pipes, если provider unavailable;
- PTY session can send and receive text;
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
