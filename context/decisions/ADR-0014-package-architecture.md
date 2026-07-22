# ADR-0014: Пакетная архитектура ядра

## Статус

Принято.

## Контекст

Пакеты должны показывать ownership и public boundary, не превращая scenario-first API в техническую навигацию.
Stateful session contracts разделяют lifecycle и exclusive stream ownership, а runtime details не должны экспортироваться
ради межпакетного доступа.

## Решение

- `io.github.ulviar.procwright` — `Procwright`, `CommandService`, общий exception boundary и scenario namespaces с
  `Draft`/`PoolDraft`.
- `io.github.ulviar.procwright.command` — command model, result, input/output/environment/shutdown policies и failures.
- `io.github.ulviar.procwright.diagnostics` — public diagnostic events и hooks.
- `io.github.ulviar.procwright.session` — sealed raw/expect/line/protocol/stream/pooled handles, protocol contracts,
  transcripts, metrics и failures.
- `io.github.ulviar.procwright.terminal` — terminal policy, request, provider, size и signal.
- `io.github.ulviar.procwright.preset` — typed transformations scenario Draft.
- `io.github.ulviar.procwright.internal` — settings, plans и process helpers.
- `io.github.ulviar.procwright.internal.session` — stateful session implementations и runtime factories.

Session-family public contracts остаются в одном package, потому что координируют единоличное владение
stdin/stdout/stderr. Разнос каждого сценария по отдельному public package добавил бы навигацию, но не новую границу
инварианта.

## Инварианты

- Public signatures не ссылаются на `internal`.
- JPMS экспортирует только public packages.
- Public session handles sealed и не являются SPI.
- Stateful lifecycle/output ownership находится в `internal.session`.
- Направления production dependencies закреплены bytecode boundary test.
- Новый package требует устойчивой ответственности, а не только уменьшения числа файлов в каталоге.

## Последствия

Root package остается коротким пользовательским entry layer. Internal decomposition можно менять без расширения
compatibility surface. Public failure constructors допустимы там, где internal implementation должна создать
scenario-specific exception, но сами implementation types остаются скрыты.

## Проверка

- `PublicApiSurfaceTest` проверяет packages, signatures и JPMS exports.
- `PackageBoundaryTest` проверяет production dependency directions.
- Javadoc gate исключает internal packages.
