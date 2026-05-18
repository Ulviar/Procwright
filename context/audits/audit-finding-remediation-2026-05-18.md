# Исправление findings двух архитектурных аудитов 2026-05-18

## Назначение

Документ фиксирует, как закрыты findings из:

- [global-architecture-audit-2026-05-18.md](global-architecture-audit-2026-05-18.md);
- [deep-component-class-audit-2026-05-18.md](deep-component-class-audit-2026-05-18.md).

## Статус

Все findings из двух аудитов закрыты кодом, тестами или явным контрактным решением. Для P3 по `CommandResult`
выбрано контрактное решение: runtime-owned results гарантируют согласованность bytes/text, а public advanced constructor
остается snapshot escape hatch и документирует ответственность caller-а.

## Закрытые findings

### Expect redaction in exception messages

- `status`: closed by code and tests.
- `code`: `DefaultExpect` теперь строит failure messages через тот же redaction/verbatim policy, что и transcript action
  values.
- `tests`: `ExpectIntegrationTest` проверяет timeout/EOF redaction для text/regex и verbatim opt-in.
- `docs`: public security и expect docs уточняют, что redaction распространяется на transcript и failure messages.

### Pooled default resolution in facade

- `status`: closed by internal owner.
- `code`: default application перенесен из `CommandService` в `PooledLineSessionInvocationDefaults`.
- `tests`: `PooledLineSessionInvocationDefaultsTest` покрывает перенос всех pool policy fields; integration test проверяет
  service-level pooled defaults на реальном pool.

### Non-SPI public session interfaces

- `status`: closed by sealed contracts.
- `code`: `Session`, `Expect`, `LineSession`, `StreamSession` и `PooledLineSession` стали sealed interfaces с единственной
  internal implementation для каждого handle.
- `tests`: `SessionContractShapeTest` фиксирует sealed shape и permitted implementation classes. `PublicApiSurfaceTest`
  разрешает internal permitted subclasses как JPMS-hidden implementation detail.
- `docs`: migration и compatibility docs обновлены.

### Diagnostics delivery ordering

- `status`: closed by explicit contract.
- `contract`: diagnostics delivery остается async best-effort and unordered; order-preserving dispatcher не добавлен,
  потому что текущая модель специально не должна блокировать listener/sink друг другом.
- `tests`: `DiagnosticsOptionsTest` проверяет, что `context/diagnostics.md` и public diagnostics reference фиксируют
  unordered/non-ordering semantics.

### Integrations JPMS descriptor

- `status`: closed by module descriptor and tests.
- `code`: `:icli-integrations` получил `module-info.java` с module name `com.github.ulviar.icli.integrations`.
- `tests`: `IntegrationModuleDescriptorTest` проверяет module name, exported package и dependency on core module.
- `docs`: release compatibility, dependency review и ADR-0009 обновлены.

### DefaultSession oversized invariant owner

- `status`: reduced by extracting a sub-owner.
- `code`: output ownership state machine перенесен из `DefaultSession` в `SessionOutputOwnership`.
- `tests`: existing `SessionOutputOwnershipTest`, `ExpectIntegrationTest`, `LineSessionIntegrationTest`,
  `StreamScenarioIntegrationTest` и `InteractiveSessionIntegrationTest` продолжают доказывать behavior.
- `note`: stdin guard и idle/exit watchers оставлены в `DefaultSession`, потому что они тесно связаны с process lifecycle.
  Дополнительное дробление без нового поведения было бы архитектурной косметикой.

### Bounded transcript invariant duplication

- `status`: closed by shared internal owner.
- `code`: `BoundedTranscriptBuffer` стал единым owner для line transcript, expect transcript и stream diagnostics window.
- `tests`: `BoundedTranscriptBufferTest` покрывает labels, action boundaries и front truncation; scenario tests продолжают
  проверять external behavior.

### Public result/metrics relational invariants

- `status`: metrics closed by code; command result closed by explicit contract.
- `code`: `PooledLineSessionMetrics` теперь отклоняет impossible snapshots: `idle > size`, `leased > size`,
  `idle + leased > size`, `retired > created`.
- `tests`: `PolicyValueTest` проверяет невозможные metrics snapshots.
- `contract`: `CommandResult` documents advanced constructor consistency responsibility; iCLI-produced results remain
  runtime-owned and charset-aligned.

### Integration protocol edge coverage

- `status`: closed by tests.
- `tests`: `ContentLengthJsonFramesTest` покрывает oversized header; `JsonCodecTest` покрывает invalid/incomplete string
  escapes and numeric edge cases.

## Проверки

Targeted checks после исправлений:

- `./gradlew test :icli-integrations:test`;
- `./gradlew integrationTest --tests com.github.ulviar.icli.ExpectIntegrationTest --tests com.github.ulviar.icli.PooledLineSessionIntegrationTest`;
- `./gradlew test :icli-integrations:test integrationTest --tests com.github.ulviar.icli.ExpectIntegrationTest --tests com.github.ulviar.icli.PooledLineSessionIntegrationTest --tests com.github.ulviar.icli.LineSessionIntegrationTest --tests com.github.ulviar.icli.StreamScenarioIntegrationTest --tests com.github.ulviar.icli.InteractiveSessionIntegrationTest`;
- `./gradlew javadoc :icli-integrations:javadoc`.

Перед завершением работы должен пройти полный release gate или явно зафиксированная причина, почему он не запускался.
