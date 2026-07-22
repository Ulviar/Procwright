# Контракт diagnostics

## Назначение

Diagnostics — наблюдательный слой поверх сценариев, а не отдельный workflow, logging framework или tracing SDK. Он
помогает связать lifecycle события одного process run, не меняя поведение команды и не раскрывая сырые пользовательские
данные.

## Инварианты

- Diagnostic listener и transcript sink подключаются через `withDiagnosticListener(...)` и
  `withDiagnosticTranscriptSink(...)` конкретного scenario Draft.
- Каждый process lifecycle получает один `runId`; события разных lifecycle получают разные `runId`.
- Delivery listener и transcript sink выполняется асинхронно best-effort; события одного process lifecycle
  доставляются каждому получателю последовательно в порядке их возникновения (`PROCESS_STARTED` приходит раньше
  `PROCESS_EXITED`).
- Pending queue каждого получателя bounded; при заблокированном получателе старые pending events могут быть отброшены.
- Ошибка listener или transcript sink, включая `Throwable`, не меняет результат процесса.
- `CommandEcho` не содержит argument values и environment values; он содержит executable, argument count, working
  directory, environment names, output mode и terminal policy.
- Diagnostic event attributes не содержат raw stdout/stderr, stdin, argv values или environment values.
- Ordering между разными получателями (listener против transcript sink) и между разными lifecycle не является
  контрактом; runtime не гарантирует ordering поверх границы получателя, корреляция идет через `runId`.
- Внутренний owner schema — `DiagnosticAttributeSchema`; Markdown ниже является consumer-facing mirror этой схемы.

## Схема событий

| Событие | Атрибуты | Значение |
| --- | --- | --- |
| `COMMAND_PREPARED` | none | Сценарий подготовил команду к запуску. |
| `PROCESS_STARTED` | `pid` | Process handle успешно создан. |
| `OUTPUT_TRUNCATED` | `source`, `limitBytes` or `limitChars` | Удерживаемое окно output или diagnostics достигло настроенной границы. |
| `TIMEOUT_REACHED` | none | Сработал timeout сценария. |
| `SHUTDOWN_REQUESTED` | `reason` | Runtime запросил shutdown из-за `timeout`, `close`, `failure`, `idleTimeout` или caller `interrupted`. |
| `LISTENER_FAILED` | none | Streaming listener выбросил ошибку при обработке chunk. |
| `PROCESS_EXITED` | `timedOut`, optional `exitCode` | Process lifecycle завершился. |
| `PROCESS_FAILED` | `error` | Launch, supervision или runtime path завершился ошибкой до нормального completion. |

Новые event types или attributes требуют теста контракта и обновления этого документа. Если атрибут может содержать
пользовательские данные, он должен быть bounded и явно обоснован отдельным ADR.

## Сценарная граница

- `run`, `interactive`, `lineSession`, `protocolSession`, `listen` и worker launches внутри nested pooled сценариев
  испускают lifecycle events.
- `Expect` не испускает отдельные process lifecycle events, потому что работает поверх уже открытого `Session`.
- Scenario transcripts (`LineTranscript`, `StreamTranscript`, `Expect` transcript) — это диагностические снимки
  конкретного helper-а, а не global diagnostics bus.
- `Expect` transcript redacts caller-provided send/expect values by default. Verbatim action values допустимы только
  через явный `Expect.Draft.withTranscriptValues(...)` и не должны использоваться для секретов.
