# Public API baseline

## Назначение

Baseline фиксирует намеренную JVM-поверхность первого выпуска. Он защищается тремя независимыми механизмами:

- surface tests проверяют public packages/types, JPMS exports и отсутствие internal/external types;
- exact signature checker сравнивает methods, generic bounds и checked `throws` с файлами
  `config/api-compatibility/0.1.0/`;
- external consumer modules компилируют канонические Java, Kotlin и integrations scenarios.

Machine-readable core/integrations signatures, Kotlin JVM signatures и Kotlin ABI baseline синхронизированы с принятым
Draft API. Это состояние source, а не зеленый same-commit release proof: перед выпуском exact signature, ABI и external
consumer gates должны успешно пройти на том же clean commit.

## Core module

`io.github.ulviar.procwright` экспортирует только:

- `io.github.ulviar.procwright`;
- `io.github.ulviar.procwright.command`;
- `io.github.ulviar.procwright.diagnostics`;
- `io.github.ulviar.procwright.preset`;
- `io.github.ulviar.procwright.session`;
- `io.github.ulviar.procwright.terminal`.

`internal` packages не экспортируются и не входят в compatibility surface.

## Public nullness contract

Все экспортируемые Java packages core и integrations помечены `@NullMarked`. Допустимые отклонения ограничены
точными `@Nullable` type-use positions и JSpecify `UNION_NULL` semantics для generated record `equals`; ослабление
через `@NullnessUnspecified` в public contract запрещено. Kotlin artifact использует собственную Kotlin nullability
metadata, а его strict consumers обязаны видеть JSpecify contract Java API. Publication/module boundary описана в
[dependency-review.md](dependency-review.md#public-nullness-metadata), владельцы и proofs — в
[invariant-proof-map.md](../quality/invariant-proof-map.md#api-и-normalization).

Каноническая форма:

```text
Procwright.command(String | CommandSpec) -> CommandService
CommandService.run() -> RunScenario.Draft -> execute()
CommandService.interactive() -> InteractiveScenario.Draft -> open()
CommandService.lineSession() -> LineSessionScenario.Draft -> open()
CommandService.listen() -> StreamScenario.Draft -> open()
CommandService.protocolSession(Supplier<ProtocolAdapter<I,O>>)
  -> ProtocolSessionScenario.Draft<I,O> -> open()
LineSessionScenario.Draft.pooled() -> LineSessionScenario.PoolDraft -> open()
ProtocolSessionScenario.Draft.pooled() -> ProtocolSessionScenario.PoolDraft -> open()
Session.expect() -> Expect.Draft -> open()
```

Обязательные surface-инварианты:

- все Draft write-only, immutable и persistent;
- scenario-specific `with*` возвращает тот же Draft family;
- process/resource создается только `execute()` или `open()`;
- protocol entry point принимает factory, создающую adapter на каждый session/worker;
- pooled configuration вложена в line/protocol scenario и не раскрывает lease;
- pool Draft задает bounded close timeout; pool handle предоставляет только synchronous `close()` и cancellation-isolated
  `closeAsync()` одного terminal cleanup;
- public scenario configuration carriers вне Draft, root pool shortcuts и второй protocol builder dialect отсутствуют;
- public handles sealed и принадлежат Procwright, а не являются SPI;
- exact baseline фиксирует `PermittedSubclasses` этих handles: реализации остаются неэкспортируемыми, но изменение их
  binary names меняет JVM-метаданные публичной sealed hierarchy;
- `ProcwrightException` остается общим unchecked catch boundary, не заменяя scenario-specific structured exceptions.

## Optional integrations

Модуль `io.github.ulviar.procwright.integrations` экспортирует только
`io.github.ulviar.procwright.integration`. Его public helpers могут ссылаться на core/Jackson types, необходимые
пользователю, но не добавляют process runtime. Exact signatures проверяются отдельным baseline и external JPMS
consumer.

## Optional Kotlin

`:procwright-kotlin` публикует package `io.github.ulviar.procwright.kotlin` и расширяет Java Draft/handles:

- overloads для `kotlin.time.Duration`;
- `RunScenario.Draft.executeAwait()`;
- `requestAwait(...)` для direct и pooled line/protocol sessions;
- detached `awaitExit()` для session handles;
- cold `StreamScenario.Draft.openFlow()`;
- `protocolAdapterFactory { ... }` с отдельным adapter wrapper на factory call.

Kotlin module не публикует mutable scenario scopes, terminal configuration lambdas, `openAwait()` или второй pool
DSL. Его JVM signatures и Kotlin ABI baseline проверяются вместе с отдельным consumer fixture.

## Изменение baseline

До первого выпуска approved surface меняется только вместе с:

- public surface tests;
- exact core/integrations/Kotlin signatures и Kotlin ABI file;
- executable external consumers;
- public documentation/examples;
- этим документом и релевантным ADR при изменении lifecycle/ownership.

После публикации изменение подчиняется [compatibility-policy.md](compatibility-policy.md) и SemVer. Baseline нельзя
перезаписывать только ради зеленого gate: diff должен соответствовать осознанному API-решению.
