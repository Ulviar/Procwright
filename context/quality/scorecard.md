# Карта качества

## Статус

Фаза 15 реализована в текущем срезе. Ветка содержит контекст clean rewrite, Gradle foundation с Java 25 baseline,
compile-tested API sketches, детерминированную process fixture, one-shot execution kernel, scenario profile resolver для
`run`, raw interactive session scenario, line-oriented request/response workflow, expect automation helper, первый PTY
transport, listen-only streaming scenario, первый diagnostics/observability слой, optional Kotlin ergonomics module и
pooled line-session scenario, scenario presets как typed builder customizers и optional CLI-backed integrations module.
`CommandService.run(...)`, `CommandService.interactive(...)` и `CommandService.lineSession(...)` уже запускают реальные
процессы через `ScenarioProfile -> LaunchPlan -> ExecutionPlan/SessionExecutionPlan`; `CommandService.listen(...)`
добавляет streaming через `StreamExecutionPlan`, а `Session.expect(...)` добавляет prompt automation поверх raw session.
Session-сценарии поддерживают `TerminalPolicy.DISABLED`, `AUTO` и `REQUIRED`; PTY доступен через узкий `PtyProvider`
SPI и системный Unix-провайдер на базе `script(1)`. Diagnostics events имеют lifecycle `runId` и подключены к
service-owned сценариям: `run`, `interactive`, `lineSession`, `listen` и worker launches внутри `pooled`. Kotlin module
дает extension/suspend/Flow API без Kotlin dependency в Java core. `CommandService.pooled(...)` переиспользует
существующий `LineSession` runtime для прогретых workers. `:icli-integrations` добавляет JSON/JSONL, Content-Length
framing, structured adapter errors, cancellable calls и command-backed tool wrappers поверх существующих сценариев.
Bounded `stressTest` входит в `check` и проверяет большие потоки, timeout churn, rapid session open/close, pooling
contention и PTY stability при доступном provider. Release hardening добавляет Apache-2.0 license, CI matrix для
Linux/macOS/Windows, versioning policy, compatibility policy, dependency review, release checklist, migration notes,
единые group/version для modules, Javadoc artifacts для Java modules, KDoc check для Kotlin API и tests, фиксирующие
публичные package boundaries по всему production artifact; production package graph теперь проверяется отдельным
class-file тестом. Core artifact имеет JPMS descriptor `com.github.ulviar.icli`; public session-family handles стали
interfaces, а stateful реализации перенесены в неэкспортируемый `internal.session`. Session shutdown escalation
hardening остается отдельным release gate до тестового закрытия или явного release limitation.

## Release-релевантные критерии

| Область | Статус | Что должно быть верно |
| --- | --- | --- |
| Engineering charter | Активно | Качество важнее скорости; требования закреплены как проектный стандарт. |
| Scenario API | Начато | `run` выбирает сценарный профиль, а не напрямую набор runtime flags. |
| Invariant model | Начато | `ScenarioProfile + CommandSpec + CommandInvocation/SessionInvocation` разворачиваются в валидированные scenario-specific plans. |
| One-shot execution | Начато | Direct argv, explicit shell, stdin, working directory, env, charset, timeout и drain покрыты integration tests. |
| Capture policy | Начато | Bounded stdout/stderr capture и truncation flags покрыты; streaming/discard еще не добавлены. |
| Timeout/shutdown | Начато | Timeout supervision, forceful shutdown и process-tree cleanup покрыты integration tests. |
| Command model | Начато | Immutable command spec, per-call invocation builder и explicit environment policy компилируются и покрыты базовыми тестами. |
| Interactive session | Начато | Raw `Session` имеет защищенный stdin, raw stdout/stderr, `onExit`, idempotent close и caller-visible idle timeout tests. |
| Line session | Начато | `LineSession` сериализует request/response, поддерживает custom decoder, bounded transcript, bounded line length, различение EOF/timeout и stderr drain. |
| Expect helper | Начато | Literal/regex matching, send/sendLine, bounded transcript, redacted action values, различение timeout/EOF и ANSI filter покрыты tests. |
| PTY | Начато | `TerminalPolicy`, `PtyProvider`, Unix `script(1)` provider из trusted system paths, explicit unsupported behavior, terminal size request и Ctrl+C-style signal mapping покрыты tests. |
| Streaming/listen | Начато | `listen` закрывает stdin по умолчанию, дренирует stdout/stderr, dispatches chunks, хранит bounded diagnostics, timeout/listener failure покрыты tests. |
| Diagnostics | Начато | Structured lifecycle/timeout/truncation events, lifecycle `runId`, redaction-friendly command echo и launch failures без raw argv/env values, async listener SPI, transcript sink и diagnostic test recorder покрыты tests для service scenarios, включая pooled worker launches. |
| Kotlin ergonomics | Начато | Optional `:icli-kotlin` module компилируется отдельно, содержит receiver extensions, suspend wrappers, узкий `ListenFlowInvocation` без listener override и Flow adapter tests без silent drops. |
| Pooling | Начато | `PooledLineSession` использует existing `LineSession` workers, поддерживает max/warmup size, acquire timeout, reset/health hooks, worker retirement, graceful drain и metrics snapshot. |
| Scenario presets | Начато | `ScenarioPresets` дает typed builder customizers для command automation, env diagnostics, REPL, prompt automation, log following, binary byte snapshots, terminal-required session и warm worker pool без нового runtime. |
| CLI integrations | Начато | Optional `:icli-integrations` содержит depth-limited JSON/JSONL codec, Content-Length framed JSON helpers, `JsonLineSession`, cancellation mapping, `ToolCallResult`, `CliAdapterError` и compile-tested command-backed tool examples без MCP dependency в core. |
| Performance/stress | Начато | `stressTest` входит в `check` и покрывает bounded capture under load, stderr drain, timeout churn, rapid session lifecycle, pooled contention и conditional PTY stability. |
| Release hardening | Начато | License, cross-platform CI, pinned workflow actions, Gradle wrapper checksum, dependency verification metadata, versioning/compatibility/dependency policies, release checklist, migration notes, unified module coordinates, JPMS descriptor for core, Javadoc artifacts, Kotlin KDoc check и public package boundary tests добавлены. |
| Fixture/evals | Начато | Process fixture моделирует success, stderr, large output, timeout, session I/O, line workflow и streaming cases. |
| Documentation | Базово закрыто | README описывает release status и verification tiers; `docs/` содержит public MkDocs Material site с overview, getting started, scenario docs, how-to guides, reference, API и release разделами; `publicDocsCheck` собирает site в strict mode и подкладывает generated Java API docs; public docs отделены от внутреннего русского `context/` и связаны с compile-tested examples через tests. |
| Raw/session affinity pooling | Отложено | Stateful affinity и raw session pooling не входят в текущий MVP-срез. |

## Решения, которые нужно принять

- Итоговые имена `CommandSpec` / `CommandService` / `RunOptions`.
- Нужно ли оставлять `CommandService` итоговым именем или переименовать до stabilization.
- Полный набор capture policies после bounded-only MVP.
- Нужно ли добавлять отдельные `Expect`-level diagnostic events до public stabilization; process lifecycle уже живет
  на уровне owning `Session`.
- Нужно ли добавлять ordered/bounded dispatcher для diagnostics transcript sink, если best-effort событий станет мало.
- Когда Kotlin compiler начнет поддерживать следующие JVM targets, сверять `:icli-kotlin` target с Java baseline.
- Нужно ли добавлять отдельные diagnostics events для pool worker lifecycle; текущий pool имеет локальные metrics.
- Какие presets стоит оставить перед public stabilization; каталог presets не должен превращаться в набор use-case runners.
- Нужен ли реальный MCP SDK adapter как отдельный модуль поверх `:icli-integrations`; core и текущий integration module
  не должны зависеть от MCP SDK.
- Нужен ли отдельный optional PTY artifact после расширения platform matrix.
- Какой Windows ConPTY provider будет добавлен: отдельный artifact или runtime-specific implementation.
- Нужны ли отдельные JMH benchmarks после стабилизации deterministic stress suite.
- Когда добавлять Maven Central publishing, signing и POM metadata; текущая ветка готовит release candidate, но не
  публикует артефакты.
- Нужно ли переименовать `SessionOptions.idleTimeout` перед публичной стабилизацией; текущая семантика зафиксирована
  как caller-visible activity: успешные записи, закрытие stdin и успешные чтения через session streams.

## Что считается прогрессом

- Новый public API компилируется в маленьких examples.
- Каждый новый behavior добавляет eval/test.
- Документы уменьшают неопределенность, а не заменяют реализацию.
