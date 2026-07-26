# Public API boundary

## Назначение

Документ фиксирует намеренную пользовательскую поверхность до первого выпуска. Это проектная граница, а не обещание
совместимости с ещё не опубликованным artifact. Она защищается тремя независимыми механизмами:

- surface tests проверяют сценарные точки входа, форму Draft API, public packages и отсутствие утечек недоступных типов;
- JPMS descriptors экспортируют только утвержденные packages;
- external consumer modules компилируют и выполняют канонические Java, Kotlin и integrations scenarios.

Kotlin ABI baseline остаётся машинной проверкой Kotlin DSL, потому что для него уже используется стандартный Gradle
инструмент. До первого Java-релиза отдельный exact-signature baseline не нужен: осознанное API-решение должно менять
surface tests, external consumers и public documentation в одном срезе. После первого выпуска binary compatibility
сравнивается стандартным инструментом с опубликованным artifact.

## Core module

`io.github.ulviar.procwright` экспортирует только:

- `io.github.ulviar.procwright`;
- `io.github.ulviar.procwright.command`;
- `io.github.ulviar.procwright.diagnostics`;
- `io.github.ulviar.procwright.session`;
- `io.github.ulviar.procwright.terminal`.

`internal` packages не экспортируются и не входят в compatibility surface.

## Public nullness contract

Все экспортируемые Java packages core и integrations помечены `@NullMarked`. Допустимые отклонения ограничены
точными `@Nullable` type-use positions и JSpecify `UNION_NULL` semantics для generated record `equals`; ослабление
через declaration-level `@NullUnmarked` в public contract запрещено. Kotlin artifact использует собственную Kotlin nullability
metadata, а его strict consumers обязаны видеть JSpecify contract Java API. Publication/module boundary описана в
[dependency-review.md](dependency-review.md#public-nullness-metadata), владельцы и proofs — в
[invariant-proof-map.md](../quality/invariant-proof-map.md#api-и-normalization).

Каноническая форма:

```text
Procwright.command(String | CommandSpec) -> CommandService
CommandService.run() -> RunScenario.Draft -> execute()
CommandService.interactive() -> InteractiveScenario.Entry -> open()
CommandService.interactive() -> InteractiveScenario.Entry -> expect() -> ExpectScenario.Draft -> open()
CommandService.lineSession() -> LineSessionScenario.Draft -> open()
CommandService.listen() -> StreamScenario.Draft -> open()
CommandService.protocolSession(Supplier<ProtocolAdapter<I,O>>)
  -> ProtocolSessionScenario.Draft<I,O> -> open()
LineSessionScenario.Draft.pooled() -> LineSessionScenario.PoolDraft -> open()
ProtocolSessionScenario.Draft.pooled() -> ProtocolSessionScenario.PoolDraft -> open()
```

Обязательные surface-инварианты:

- все Draft write-only, immutable и persistent;
- scenario-specific `with*` возвращает тот же Draft family;
- process/resource создается только `execute()` или `open()`;
- protocol entry point принимает factory, создающую adapter на каждый session/worker;
- pooled configuration вложена в line/protocol scenario и не раскрывает lease;
- line/protocol pool handles остаются разными сценариями, но возвращают общий `PooledSessionMetrics` и используют
  общий `PooledSessionException` для pool lifecycle; request-level exceptions остаются сценарными;
- public scenario configuration carriers вне Draft, root pool shortcuts и второй protocol builder dialect отсутствуют;
- public handles sealed и принадлежат Procwright, а не являются SPI;
- release-locked `SessionContractShapeTest` проверяет sealed nature, non-SPI contract и точные
  `PermittedSubclasses`;
- `ProcwrightException` остается общим unchecked catch boundary, не заменяя scenario-specific structured exceptions.

Поведенческие гарантии, defaults, limits и lifecycle принадлежат
[scenario-contracts.md](../scenario-contracts.md), а не этому перечню API-формы.

## Optional integrations

Модуль `io.github.ulviar.procwright.integrations` экспортирует только
`io.github.ulviar.procwright.integration`. Его public helpers могут ссылаться на core/Jackson types, необходимые
пользователю, но не добавляют process runtime. Граница проверяется module descriptor, surface tests и external JPMS
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
DSL. Его Kotlin ABI baseline проверяется вместе с отдельным consumer fixture.

## Изменение поверхности

До первого выпуска утвержденная поверхность меняется только вместе с:

- public surface tests;
- Kotlin ABI file, если затронут Kotlin DSL;
- executable external consumers;
- public documentation/examples;
- этим документом и релевантным ADR при изменении lifecycle/ownership.

После публикации изменение подчиняется [compatibility-policy.md](compatibility-policy.md) и SemVer.
