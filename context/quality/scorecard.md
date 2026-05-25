# Карта качества

## Статус

Текущий срез содержит Java 17/21/25 release variants,
scenario-first public API, общий execution/session kernel, PTY capability boundary, diagnostics, optional Kotlin ergonomics,
pooled line-session scenario, protocol-session scenario, typed protocol pool, scenario presets, optional CLI-backed
integrations, bounded stress suite, comparison research module, public MkDocs site и generated Java API docs для
core/integrations.

Основной roadmap до первого публичного release baseline выполнен. Дальнейшая работа перед публикацией должна быть
stabilization/release-focused: не расширять сценарии без ADR/eval, не добавлять shortcuts, которые создают второй API
dialect, и не переносить optional/platform/runtime зависимости в core.

## Release baseline

| Область | Статус | Что верно сейчас |
| --- | --- | --- |
| Engineering charter | Активно | Качество, инварианты, TDD/evals и документация остаются обязательным стандартом проекта. |
| Scenario API | Baseline | Пользователь выбирает `run`, `interactive`, `lineSession`, `protocolSession`, `expect`, `listen`, `lineSession().pooled()` или `protocolSession(factory).pooled()`, а не собирает runtime flags. |
| Invariant model | Baseline | `ScenarioProfile + CommandSpec + scenario invocation` разворачиваются в валидированные execution/session plans. |
| One-shot execution | Baseline | Direct argv, explicit shell, stdin, cwd/env, charset, timeout, drain, bounded capture и typed result покрыты tests. |
| Capture policy | Baseline | Bounded capture и truncation flags реализованы; streaming/discard capture policies не входят в первый релиз. |
| Timeout/shutdown | Baseline | Timeout supervision и process-tree cleanup покрыты integration/stress tests; platform timing остается bounded regression, а не performance guarantee. |
| Command model | Baseline | Immutable `CommandSpec`, per-call builders, explicit environment policy и result/error model покрыты unit/integration tests. |
| Interactive session | Baseline | Raw `Session` имеет guarded stdin, raw stdout/stderr, `onExit`, idempotent close и caller-visible idle timeout. |
| Line session | Baseline | `LineSession` сериализует request/response, поддерживает custom decoder, bounded transcript, bounded line length, EOF/timeout distinction и stderr drain. |
| Protocol session | Baseline | `ProtocolSession` сериализует framed/typed request/response через adapter, поддерживает readiness, strict charset, request/response limits, bounded transcript и typed failure taxonomy. |
| Expect helper | Baseline | Literal/regex matching, send/sendLine, bounded transcript, redacted action values и failure messages, ANSI filter и EOF/timeout distinction покрыты tests. |
| PTY | Baseline | `TerminalPolicy`, `PtyProvider`, system provider, explicit unsupported behavior, terminal size и terminal signal model покрыты; Windows ConPTY отложен. |
| Streaming/listen | Baseline | `listen` закрывает stdin по умолчанию, дренирует stdout/stderr, dispatches chunks, хранит bounded diagnostics, покрывает timeout/listener failure. |
| Diagnostics | Baseline | Structured lifecycle/timeout/truncation events, lifecycle `runId`, redaction-friendly command echo, async best-effort unordered delivery и transcript sink покрыты tests/docs. |
| Kotlin ergonomics | Baseline | Optional `:icli-kotlin` содержит extensions, suspend wrappers и Flow adapter; Java core не зависит от Kotlin; KDoc coverage check включен. |
| Pooling | Baseline | `PooledLineSession` и `PooledProtocolSession` используют existing session workers, поддерживают max/warmup/minIdle, acquire timeout, bounded reset/health hooks, per-worker protocol adapters, retirement reasons, drain и metrics. |
| Scenario presets | Baseline | Текущий набор presets заморожен для первого релиза и остается typed builder customizer layer без нового runtime. |
| CLI integrations | Baseline | Optional `:icli-integrations` содержит JSON/JSONL, Content-Length framing, protocol adapters, cancellation/error mapping и command-backed tool wrappers без MCP dependency. |
| Performance/stress | Baseline | `stressTest` входит в `check`; JMH/comparison остаются research/manual data, не performance guarantee. |
| Release hardening | Baseline | License, CI matrix, dependency verification, versioning/compatibility/dependency policies, release checklist, JPMS, Javadocs и public package boundary tests добавлены. |
| Java release variants | Baseline | Один source tree собирается с `icli.javaRelease=17/21/25`; threading model скрыта за internal boundary, Java 17 использует fallback без изменения public API. |
| Fixture/evals | Baseline | `:icli-test-cli` моделирует success, stderr, large output, timeout, sessions, streaming и нестабильные real-world process behaviors. |
| Documentation | Baseline | Public MkDocs site описывает shipped behavior, содержит scenario/how-to/reference/release pages и включает generated Java API docs. |

## Принятые стабилизационные решения

- `CommandService` остается главным entry point перед первым релизом.
- Convenience one-line shortcuts не добавляются перед первым релизом.
- `SessionOptions.idleTimeout` сохраняет имя и caller-visible activity semantics.
- Текущий набор `ScenarioPresets` заморожен; новые presets требуют ADR/eval.
- Session-family handles остаются sealed public non-SPI contracts.
- Diagnostics delivery остается async best-effort unordered.
- Expect-level action diagnostics и подробные pool worker lifecycle events отложены.
- Windows ConPTY provider отложен в optional/runtime-specific future artifact.
- Kotlin generated docs через Dokka отложены; KDoc coverage check остается release gate.
- Maven Central publishing/signing setup добавлен; первая публикация ожидает verified namespace и Central Portal
  credentials.

## Отложено за пределы первого релиза

- Raw session pooling.
- Generic/core async request API.
- Stateful affinity pools.
- Real MCP SDK adapter поверх `:icli-integrations`.
- Windows ConPTY provider.
- Dokka publication для Kotlin API docs.
- Automatic Maven Central publish без ручной проверки первого Central Portal deployment.
- Machine-dependent performance promises.
- Новые capture policy modes beyond bounded one-shot capture.

## Следующий release-focused шаг

1. Прогнать полный `./gradlew releaseCandidateCheck` на clean worktree.
2. Проверить CI на Linux/macOS/Windows.
3. Cut GitHub release, чтобы release-only CI job загрузил Central Portal deployment.

## Что считается прогрессом

- Public API freeze audit остается без P0/P1 findings.
- Каждый новый behavior добавляет owner в `quality/invariant-proof-map.md`.
- Public docs описывают только behavior, доказанный tests/examples/release context.
- Release-relevant changes обновляют `context/release/`, public docs и release gate.
