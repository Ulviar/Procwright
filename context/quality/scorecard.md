# Карта качества

## Статус

Текущий срез прошел release-stabilization pass 2026-05-18. Ветка содержит clean rewrite, Java 25 baseline, scenario-first
public API, общий execution/session kernel, PTY capability boundary, diagnostics, optional Kotlin ergonomics,
pooled line-session scenario, scenario presets, optional CLI-backed integrations, bounded stress suite, comparison
research module, public MkDocs site и generated Java API docs для core/integrations.

Основной roadmap до release hardening выполнен для первого release-candidate baseline. Дальнейшая работа перед
публикацией должна быть stabilization/release-focused: не расширять сценарии без ADR/eval, не добавлять shortcuts,
которые создают второй API dialect, и не переносить optional/platform/runtime зависимости в core.

## Release-candidate baseline

| Область | Статус | Что верно сейчас |
| --- | --- | --- |
| Engineering charter | Активно | Качество, инварианты, TDD/evals и документация остаются обязательным стандартом проекта. |
| Scenario API | RC baseline | Пользователь выбирает `run`, `interactive`, `lineSession`, `expect`, `listen` или `pooled`, а не собирает runtime flags. |
| Invariant model | RC baseline | `ScenarioProfile + CommandSpec + scenario invocation` разворачиваются в валидированные execution/session plans. |
| One-shot execution | RC baseline | Direct argv, explicit shell, stdin, cwd/env, charset, timeout, drain, bounded capture и typed result покрыты tests. |
| Capture policy | RC baseline | Bounded capture и truncation flags реализованы; streaming/discard capture policies не входят в первый RC. |
| Timeout/shutdown | RC baseline | Timeout supervision и process-tree cleanup покрыты integration/stress tests; platform timing остается bounded regression, а не performance guarantee. |
| Command model | RC baseline | Immutable `CommandSpec`, per-call builders, explicit environment policy и result/error model покрыты unit/integration tests. |
| Interactive session | RC baseline | Raw `Session` имеет guarded stdin, raw stdout/stderr, `onExit`, idempotent close и caller-visible idle timeout. |
| Line session | RC baseline | `LineSession` сериализует request/response, поддерживает custom decoder, bounded transcript, bounded line length, EOF/timeout distinction и stderr drain. |
| Expect helper | RC baseline | Literal/regex matching, send/sendLine, bounded transcript, redacted action values и failure messages, ANSI filter и EOF/timeout distinction покрыты tests. |
| PTY | RC baseline | `TerminalPolicy`, `PtyProvider`, system provider, explicit unsupported behavior, terminal size и terminal signal model покрыты; Windows ConPTY отложен. |
| Streaming/listen | RC baseline | `listen` закрывает stdin по умолчанию, дренирует stdout/stderr, dispatches chunks, хранит bounded diagnostics, покрывает timeout/listener failure. |
| Diagnostics | RC baseline | Structured lifecycle/timeout/truncation events, lifecycle `runId`, redaction-friendly command echo, async best-effort unordered delivery и transcript sink покрыты tests/docs. |
| Kotlin ergonomics | RC baseline | Optional `:icli-kotlin` содержит extensions, suspend wrappers и Flow adapter; Java core не зависит от Kotlin; KDoc coverage check включен. |
| Pooling | RC baseline | `PooledLineSession` использует existing `LineSession` workers, поддерживает max/warmup size, acquire timeout, reset/health hooks, retirement, drain и metrics. |
| Scenario presets | RC baseline | Текущий набор presets заморожен для первого RC и остается typed builder customizer layer без нового runtime. |
| CLI integrations | RC baseline | Optional `:icli-integrations` содержит JSON/JSONL, Content-Length framing, cancellation/error mapping и command-backed tool wrappers без MCP dependency. |
| Performance/stress | RC baseline | `stressTest` входит в `check`; JMH/comparison остаются research/manual data, не performance guarantee. |
| Release hardening | RC baseline | License, CI matrix, dependency verification, versioning/compatibility/dependency policies, release checklist, JPMS, Javadocs и public package boundary tests добавлены. |
| Fixture/evals | RC baseline | Process fixture и `:icli-test-cli` моделируют success, stderr, large output, timeout, sessions, streaming и нестабильные real-world process behaviors. |
| Documentation | RC baseline | Public MkDocs site описывает shipped behavior, содержит scenario/how-to/reference/release pages и включает generated Java API docs. |

## Принятые стабилизационные решения

- `CommandService` остается главным entry point перед первым RC.
- Convenience one-line shortcuts не добавляются перед первым RC.
- `SessionOptions.idleTimeout` сохраняет имя и caller-visible activity semantics.
- Текущий набор `ScenarioPresets` заморожен; новые presets требуют ADR/eval.
- Session-family handles остаются sealed public non-SPI contracts.
- Diagnostics delivery остается async best-effort unordered.
- Expect-level action diagnostics и подробные pool worker lifecycle events отложены.
- Windows ConPTY provider отложен в optional/runtime-specific future artifact.
- Kotlin generated docs через Dokka отложены; KDoc coverage check остается release gate.
- Maven Central publishing/signing не добавлены в этот stabilization pass и требуют отдельного implementation step.

## Отложено за пределы первого RC

- Raw session pooling.
- Stateful affinity pools.
- Real MCP SDK adapter поверх `:icli-integrations`.
- Windows ConPTY provider.
- Dokka publication для Kotlin API docs.
- Maven Central publishing implementation, signing и final POM metadata.
- Machine-dependent performance promises.
- Новые capture policy modes beyond bounded one-shot capture.

## Следующий release-focused шаг

1. Реализовать publishing/signing setup отдельным focused change по ADR-0017.
2. Обновить `docs/release/installation.md` фактическими published coordinates после publishing implementation.
3. Прогнать полный `./gradlew releaseCandidateCheck` на clean worktree.
4. Проверить CI на Linux/macOS/Windows.
5. Подготовить versioned public release notes на основе `docs/release/release-notes.md`.

## Что считается прогрессом

- Public API freeze audit остается без P0/P1 findings.
- Каждый новый behavior добавляет owner в `quality/invariant-proof-map.md`.
- Public docs описывают только behavior, доказанный tests/examples/release context.
- Release-relevant changes обновляют `context/release/`, public docs и release gate.
