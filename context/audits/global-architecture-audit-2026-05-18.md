# Глобальный архитектурный аудит 2026-05-18

## Назначение

Аудит проверяет текущий срез iCLI сверху вниз: от цели проекта и Gradle-модулей до пакетов, публичных контрактов,
runtime-компонентов и отдельных классов. Фокус — внутренняя целостность, согласованность решений и владельцы
инвариантов.

Аудит не исправляет найденные проблемы. Он фиксирует архитектурную картину и рекомендуемый порядок следующих действий.

Статус исправлений зафиксирован в
[audit-finding-remediation-2026-05-18.md](audit-finding-remediation-2026-05-18.md).

## Как организован аудит

Проверка разделена на шесть уровней.

1. **L0: миссия и философия.** Сверка с `architecture.md`, `scenario-api.md`, `scenario-contracts.md`,
   `invariant-architecture.md` и quality charter.
2. **L1: границы артефактов.** Gradle subprojects, release gates, dependency boundary, optional modules и
   comparison-only зависимости.
3. **L2: JPMS и пакеты.** Экспортируемые core packages, закрытие `internal`, production dependency graph и Javadoc
   boundary.
4. **L3: scenario API.** Проверка того, что пользователь выбирает workflow, а не собирает низкоуровневый process
   harness.
5. **L4: владельцы инвариантов.** Где живут timeout, shutdown, output ownership, bounded retention, redaction,
   diagnostics, cancellation и pool lifecycle.
6. **L5: классы и локальная согласованность.** Hotspot review классов `CommandService`, `ExecutionPlanResolver`,
   `ProcessKernel`, `DefaultSession`, `DefaultLineSession`, `DefaultExpect`, `DefaultPooledLineSession`,
   `DiagnosticEmitter`, `SystemPtyProvider` и integration helpers.

## Итоговая оценка

Текущая архитектура в целом согласована с исходной философией rewrite:

- core остается scenario-first библиотекой вокруг JDK process APIs;
- public entry point по-прежнему `CommandService`, а не набор независимых runners;
- `CommandSpec`, invocation builders и options остаются draft/config layer, а runtime получает resolved plans;
- `internal` и `internal.session` закрыты JPMS descriptor-ом core module;
- package boundary и public API surface закреплены тестами;
- optional `:icli-kotlin`, `:icli-integrations` и `:icli-comparison` не ломают core dependency boundary;
- integration layer не добавляет второй process runtime, а работает поверх core-сценариев.

Главный архитектурный долг сейчас не в верхнеуровневой форме, а в нескольких локальных местах, где инвариант уже
описан как принцип, но его owner в коде еще недостаточно силен.

## Findings

### P2: Expect redaction не полностью покрывает failure messages

- `file`: `src/main/java/com/github/ulviar/icli/internal/session/DefaultExpect.java`
- `line`: 100, 110, 134, 145, 291
- `problem`: transcript action values проходят через `transcriptValue(...)` и по умолчанию редактируются, но timeout/EOF
  messages для `expectText` и `expectRegex` включают raw expected text/pattern.
- `impact`: документация и scenario contracts обещают, что caller-provided send/expect values в expect transcript
  redacted by default. На практике секрет, переданный как expected text или regex, не попадает в transcript, но может
  попасть в `ExpectException.getMessage()`. Это размывает security/redaction invariant: один канал redacted, другой
  остается raw.
- `recommended fix`: сделать diagnostic message policy-aware. В default `ExpectTranscriptValues.REDACTED` exception
  message должен использовать redacted expected description без raw value; в `VERBATIM` можно оставить printable
  value. Добавить regression tests для timeout и EOF по text/regex, проверяющие отсутствие secret value в exception
  message при default options.

### P2: defaults pooled-сценария применяются в facade, а не в resolver/domain boundary

- `file`: `src/main/java/com/github/ulviar/icli/CommandService.java`
- `line`: 402
- `problem`: `CommandService.pooledInvocationBuilder()` вручную переносит поля из `PooledLineSessionOptions` в
  `PooledLineSessionInvocation.Builder`.
- `impact`: public API остается чистым, но владелец инварианта default resolution размыт. При добавлении нового поля в
  `PooledLineSessionOptions` нужно помнить о ручной синхронизации в facade. Это локальный риск рассинхронизации
  сценарного API и runtime policy.
- `recommended fix`: добавить internal resolver/default-application boundary для pooled invocation, например
  `PooledLineSessionInvocationResolver` или internal draft resolver рядом с `internal.session`. Public builder не должен
  получать public overload с defaults, но default resolution должен жить вне `CommandService`.

### P3: public session interfaces остаются осознанным non-SPI компромиссом

- `file`: `src/main/java/com/github/ulviar/icli/session/Session.java`
- `line`: 16
- `file`: `src/main/java/com/github/ulviar/icli/session/Expect.java`
- `line`: 13, 37
- `file`: `src/main/java/com/github/ulviar/icli/internal/session/SessionInternals.java`
- `line`: 10
- `problem`: `Session` и `Expect` выглядят как обычные Java interfaces, но higher-level helpers поддерживают только
  iCLI-created handles через `SessionInternals.requireDefaultSession(...)`.
- `impact`: это уже описано в Javadoc, migration docs и ADR-0015, поэтому это не текущий blocker. Но форма API все еще
  может провоцировать пользователей на custom implementations, которые runtime не поддерживает.
- `recommended fix`: оставить как принятый pre-1.0 компромисс либо до stabilization оценить sealed/opaque-handle
  вариант. Не менять сейчас без отдельного ADR, потому что текущий split хорошо закрывает JPMS/internal boundary.

### P3: diagnostics delivery semantics остаются best-effort, но не зафиксированы как ordering contract

- `file`: `src/main/java/com/github/ulviar/icli/internal/DiagnosticEmitter.java`
- `line`: 68, 71
- `problem`: каждый listener/transcript delivery запускается в отдельном virtual thread. Это сохраняет diagnostics
  observational, но не дает order/backpressure guarantees между событиями одного `runId`.
- `impact`: для текущих lifecycle events риск низкий. Если diagnostics станет richer event stream, пользователи могут
  ожидать ordered transcript, а implementation этого не гарантирует.
- `recommended fix`: либо явно задокументировать diagnostics как best-effort unordered delivery, либо добавить
  order-preserving bounded dispatcher как отдельный invariant owner до расширения diagnostics events.

### P3: optional Java integration artifact не имеет JPMS descriptor

- `file`: `icli-integrations/build.gradle.kts`
- `line`: 1
- `problem`: core уже является named module `com.github.ulviar.icli`, но `:icli-integrations` остается обычным Java
  artifact без `module-info.java`.
- `impact`: это не противоречит ADR-0015, где JPMS явно применен к core. Но release docs говорят о Java modules во
  множественном числе для Javadoc gates, и будущий public release может получить разную modularity story для core и
  integrations.
- `recommended fix`: перед release stabilization принять явное решение: либо добавить named JPMS module для
  `:icli-integrations`, либо зафиксировать, что JPMS boundary гарантируется только core artifact. В обоих случаях
  обновить release docs, чтобы формулировка была однозначной.

## Сильные архитектурные места

### Scenario-first API

`CommandService` сохраняет правильную форму: `run`, `interactive`, `lineSession`, `listen`, `pooled` и options layer не
расползаются в публичный process harness. `ScenarioPresets` остаются typed builder customizers и не запускают процессы.

### Invariant owners

- Launch composition живет в `ExecutionPlanResolver`.
- Process start/shutdown/tree cleanup живет в `ProcessLifecycle`.
- One-shot capture/drain/timeout orchestration живет в `ProcessKernel`.
- Session lifecycle, idle timeout и output ownership живут в `DefaultSession`.
- Line protocol serialization, bounded backlog и transcript живут в `DefaultLineSession`.
- Pool lifecycle и worker retirement живут в `DefaultPooledLineSession`.
- PTY capability спрятана за `TerminalPolicy`, `PtyProvider` и `ProcessTransport`.
- JSON/JSONL/tool helpers живут в optional `:icli-integrations` и не создают второй process runtime.

### Package/Jpms boundary

`module-info.java` экспортирует только:

- `com.github.ulviar.icli`;
- `com.github.ulviar.icli.command`;
- `com.github.ulviar.icli.diagnostics`;
- `com.github.ulviar.icli.preset`;
- `com.github.ulviar.icli.session`;
- `com.github.ulviar.icli.terminal`.

`PublicApiSurfaceTest` и `PackageBoundaryTest` делают это проверяемым свойством, а не только архитектурным текстом.

### Release gates

Текущие gates хорошо соответствуют maturity goals: `quickCheck`, `scenarioCheck`, `regressionCheck`,
`publicJavaJavadocCheck`, `publicDocsCheck`, `externalLibraryBoundaryCheck` и `releaseCandidateCheck` дают реальную
страховку от regression, dependency leaks и public API drift.

## Рекомендуемый порядок исправлений

1. Закрыть `P2` по expect redaction: это security/documentation consistency issue.
2. Закрыть `P2` по pooled default resolution: это небольшой архитектурный cleanup без изменения public API.
3. Принять явное решение по JPMS story для optional Java integrations перед release stabilization.
4. Задокументировать или усилить diagnostics delivery semantics до расширения diagnostics event stream.
5. Оставить non-SPI session interfaces как принятый tradeoff, пока нет отдельного решения менять shape публичного API.

## Следующий аудит после исправлений

После исправления пунктов P2 нужен targeted re-audit:

- `DefaultExpect`, `ExpectIntegrationTest`, `docs/reference/security.md`, `docs/scenarios/expect.md`;
- `CommandService`, pooled invocation tests, `ExecutionPlanResolver` или новый internal pooled resolver;
- `PublicApiSurfaceTest` и `PackageBoundaryTest`, если появятся новые internal/public типы.
