# Карта качества

## Текущий срез

Procwright имеет единый scenario-first Draft API, Java core, optional Kotlin и integrations modules, line/protocol
pools, PTY capability boundary, diagnostics, test CLI, bounded stress suite, external consumer fixtures и release
toolchain. Публичный artifact и documentation site не считаются доступными до первого выпуска.

Scorecard фиксирует текущее состояние source и наличие proof-механизма. Синхронизация source, baselines, examples и
документации не означает, что итоговые gates уже прошли вместе на одном commit.

| Область | Состояние | Текущий контракт |
| --- | --- | --- |
| Scenario API | Готово | `Procwright.command(...)` -> scenario -> persistent `Draft.with*` -> `execute/open`. |
| Draft semantics | Готово | Branching, defensive copying, no-launch-before-terminal, repeated/concurrent terminals, pool snapshots, factory behavior и `Expect.Draft` ownership покрыты. |
| Command model | Готово | Immutable `CommandSpec`, direct argv default, explicit shell/environment policy. |
| One-shot | Готово | Input, bounded/file/discard capture, strict decoding, timeout, tree shutdown и typed result/failure. |
| Interactive | Готово | Guarded stdin, raw output ownership, readiness, idle timeout, PTY и idempotent lifecycle. |
| Expect | Готово | Explicit Draft/open, bounded matching/transcript, redaction и typed outcomes. |
| Line session | Готово | Serialized requests, end-to-end deadline, independent limits/backlog и hostile-decoder protection. |
| Protocol session | Готово | Factory per session/worker, adapter-owned framing, strict decoding, global response budget и typed failures. |
| Streaming | Готово | Backpressure, bounded diagnostics, fixed closed-stdin invariant и stable listener/read/process reasons. |
| Pooling | Готово | Nested `PoolDraft`, no public lease, bounded startup/hooks/retirement, replenishment и metrics. |
| Diagnostics | Готово | Scenario-level hooks, bounded async best-effort delivery, schema и `runId`. |
| PTY | Ограничено платформой | System provider на поддерживаемых POSIX-системах; `REQUIRED` не fallback-ится; ConPTY отсутствует. |
| Kotlin | Синхронизировано | Реализация, ABI baseline и внешний Kotlin consumer используют Java Draft, durations, coroutine ownership, cold `openFlow()` и factory DSL. |
| Integrations | Готово | JSON/JSONL, Content-Length и command-backed helpers поверх core runtime; Jackson только в optional module. |
| Memory/concurrency | Готово на уровне contracts | Bounded capture/transcripts/queues/executors и stress proofs; абсолютные heap/throughput guarantees не даются. |
| Public consumers | Синхронизировано | Java, Kotlin и integrations consumers используют текущий API; итоговый compilation proof требует запуска gate на release commit. |
| API compatibility | Синхронизировано | Exact JVM signatures и Kotlin ABI baseline соответствуют принятому Draft API; совместимость доказывает только успешный gate на том же commit. |
| Documentation | Синхронизировано | Context, public docs, snippets и canonical examples описывают текущий API; итоговый strict docs proof еще должен пройти на release commit. |
| Java/platform matrix | Проверяется CI | Java 17 target на JDK 17/21/25 и Linux/macOS/Windows; source targets 21/25 отдельно. |
| Publication | Настроено, не выпущено | Signed Central bundle, isolated consumers и exact staging provenance входят в release gate. |

## Блокеры первого выпуска

- Прогнать `releaseCandidateCheck` и isolated local publication/consumer smoke на одном clean release commit.
- Получить зеленую Linux/macOS/Windows × JDK 17/21/25 CI-матрицу для exact release commit.

## Устойчивые границы

- Generic Java async API, raw session pooling, public leases и stateful affinity не входят в core.
- External process libraries остаются только в comparison module.
- Kotlin и integrations не создают второй process runtime.
- Новая настройка добавляется только scenario Draft, где имеет однозначную семантику.
- Новая возможность без владельца инварианта и executable proof не считается прогрессом.

Фактический release gate описан в [release-checklist.md](../release/release-checklist.md), а связи инвариантов с
проверками — в [invariant-proof-map.md](invariant-proof-map.md).
