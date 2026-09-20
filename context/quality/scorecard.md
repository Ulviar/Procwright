# Карта качества

## Текущий срез

Procwright имеет единый scenario-first Draft API, Java core, optional Kotlin и integrations modules, line/protocol
pools, PTY capability boundary, diagnostics, test CLI, bounded stress suite, external consumer fixtures и Maven
publication metadata. Текущая версия `0.1.0` всех трёх модулей опубликована в Maven Central; внешние consumers
проверяют установку через Gradle metadata и Maven POM-only resolution.

Scorecard оценивает текущий MVP в пределах [бюджета гарантий runtime](../decisions/ADR-0025-runtime-guarantee-budget.md).
Оценка 10/10 означает, что согласованные критерии выполнены, доказательства проверены и независимый аудит не оставил
существенных замечаний. Это инженерная оценка определённой области, а не обещание отсутствия любых будущих дефектов.
Новые изменения требуют повторной проверки затронутых инвариантов; release gates выполняются на exact release commit.

## Технические критерии

| Критерий | Оценка | Основание |
| --- | --- | --- |
| Дизайн публичного API | 10/10 | Восемь scenario paths имеют persistent Draft, явный resource terminal, раннюю validation и согласованные Java/Kotlin/integrations contracts. Проверки: public surface/nullness, Draft reuse, внешние consumers. |
| Архитектурные границы | 10/10 | Один process runtime; invariant owners и переходы указаны в proof map. Optional layers не дублируют запуск, deadlines или cleanup; JPMS и dependency boundary проверяются gate. |
| Поддерживаемость реализации | 10/10 | Startup, replenishment, retirement, terminal/output и scan/tree owners разобраны по состояниям и связям. Удалены лишние cancellation/reporting/close протоколы; сохранённые стабильный retirement future, bounded dispatch и terminal phases имеют наблюдаемое назначение. |
| Отказы и ресурсы | 10/10 | Deadline, handoff/cancellation, output ownership, retention и logical/physical cleanup имеют явные границы. First outcome, bounded failure details, slot recovery и best-effort tree cleanup защищены негативными и конкурентными tests. |
| Система тестирования | 10/10 | Invariant → owner → behavioral proof; unit, реальные процессы, bounded stress, canonical consumers, publication metadata/POM-only smoke и Java 25 CI. Regression tests проверяют отказ и восстановление, а не только форму реализации. |
| Документация разработчика | 10/10 | Task walkthrough покрывает выбор сценария, запуск, failure и cleanup. Канонические Java/Kotlin/integrations примеры исполняются; Javadoc, KDoc, MkDocs и context links имеют строгие gates. |

Переносимость, доказанная производительность и устойчивость сопровождения командой не включены в эти оценки.
Ограничения PTY и отсутствие performance guarantees сохраняются.

## Состояние возможностей

Line/protocol API задаёт размеры сообщений, а внутренние output buffers следуют response limits. Отдельных настроек
очереди и незавершённой строки нет; превышение response или его pending output имеет один `RESPONSE_TOO_LARGE`.
Transport tests доказывают полный burst выше стандартных defaults до начала чтения, а также overflow и framing boundaries.

Для знакомства доступны установка из Maven Central и checkout demos `demoRun`, `demoWorker` и `demoPool`.
JSON Lines walkthrough показывает application-owned service, повторные вызовы одной session и переход к конкурентным независимым requests через тот же
protocol Draft. Короткие Java/Kotlin фрагменты сверяются с именованными участками компилируемых examples; Markdown
показывает одинаковый код на GitHub и сайте. Этот механизм доказывает исполнимость и отсутствие drift, но сам по себе не
измеряет время освоения API новым пользователем.

Pool не создаёт timed tasks для отсутствующих hooks и не ждёт доставки late failure notifications. Line requests
проверяются до acquire, а encoded array создаётся после получения worker; локальный failure подготовки сохраняет
незатронутый worker. Эти упрощения не добавляют public settings; отсутствие лишних hooks, exact-once lease return и
независимость retirement от reporting проверяются отдельными unit и process integration tests.

| Область | Состояние | Текущий контракт |
| --- | --- | --- |
| Scenario API | Готово | `Procwright.command(...)` -> scenario -> persistent `Draft.with*` -> `execute/open`. |
| Draft semantics | Готово | Branching, defensive copying, no-launch-before-terminal, repeated/concurrent terminals, pool snapshots, factory behavior и `ExpectScenario.Draft` reuse покрыты. |
| Command model | Готово | Immutable `CommandSpec`, direct argv default, explicit shell/environment policy. |
| One-shot | Готово | Input, bounded/file/discard capture, strict decoding, timeout, tree shutdown и typed result/failure. |
| Interactive | Готово | Guarded stdin, raw output ownership, readiness, idle timeout, PTY и idempotent lifecycle. |
| Expect | Готово | Explicit Draft/open, bounded matching/transcript, redaction и typed outcomes. |
| Line session | Готово | Serialized requests, end-to-end deadline, response-sized output bounds и hostile-decoder protection. |
| Protocol session | Готово | Factory per session/worker, adapter-owned framing, strict decoding, global response budget и typed failures. |
| Streaming | Готово | Backpressure, bounded diagnostics, fixed closed-stdin invariant и stable listener/read/process reasons. |
| Pooling | Готово | Nested `PoolDraft`, no public lease, общий lifecycle metrics/failure API, per-pool `maxSize` 1..256, deadline-bound caller wait для startup/hooks, bounded retirement queue с caller-runs backpressure и одношаговый scheduled replenishment. |
| Diagnostics | Готово | Scenario-level hooks, bounded async best-effort delivery, schema и `runId`. |
| PTY | Ограничено платформой | System provider на поддерживаемых POSIX-системах; `REQUIRED` не fallback-ится; ConPTY отсутствует. |
| Kotlin | Готово | Реализация, ABI baseline и внешний Kotlin consumer используют Java Draft, durations, coroutine ownership, cold `openFlow()` и factory DSL. |
| Integrations | Готово | JSON Lines, delimiter, Content-Length и typed Jackson adapters поверх `protocolSession`; Jackson только в optional module. |
| Memory/concurrency | Готово на уровне contracts | Bounded retained data и per-scenario queues, fixed execution concurrency, один pending replenishment turn на live pool и stress proofs; абсолютные heap/throughput guarantees не даются. |
| Public consumers | Готово | Java, Kotlin и integrations consumers компилируются и исполняются через source dependency и опубликованные Maven metadata/POM-only artifacts. |
| API boundary | Surface gates готовы | Java surface защищают целевые tests, JPMS и external consumers; Kotlin DSL имеет ABI baseline. Java binary compatibility gate пока не подключён. |
| Documentation | Готово | Public docs, context owners, snippets и canonical examples описывают текущий API и pool lifecycle contract; strict docs и executable examples входят в gate. |
| Java/platform matrix | Проверяется CI | Java 25 runtime/target на Linux/macOS/Windows; major 69 и JVM 25 metadata всех public artifacts. |
| Publication | Опубликовано `0.1.0` | Три модуля доступны из Maven Central; isolated и external normal/POM-only consumers проверяют Maven metadata и использование артефактов. Процесс публикации сохраняет отдельную валидацию кандидата перед выпуском. |

До первого изменения public API после `0.1.0` обязателен
[compatibility bootstrap](../release/compatibility-policy.md#стабильность-публичного-api). До его выполнения public API
не меняется.

## Устойчивые границы

- Generic Java async API, raw session pooling, public leases и stateful affinity не входят в core.
- Public artifacts не зависят от стороннего process runtime.
- Kotlin и integrations не создают второй process runtime.
- Новая настройка добавляется только scenario Draft, где имеет однозначную семантику.
- Новая возможность без владельца инварианта и executable proof не считается прогрессом.

Процесс публикации и проверки артефактов описан в [publication-readiness.md](../release/publication-readiness.md),
а связи инвариантов с проверками — в [invariant-proof-map.md](invariant-proof-map.md).
