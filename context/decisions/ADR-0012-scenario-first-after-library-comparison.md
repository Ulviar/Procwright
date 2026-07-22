# ADR-0012: Scenario-first core и граница внешних process-библиотек

## Статус

Принято.

## Контекст

One-shot запуск можно выразить многими библиотеками, но долгоживущие CLI workflows требуют согласованных lifecycle,
stream ownership, timeout, bounded retention, protocol и diagnostics инвариантов. Public API не должен перекладывать их
сборку на пользователя или зависеть от abstractions одного backend.

## Решение

Procwright остается самостоятельным scenario-first runtime:

- пользователь выбирает `run`, `interactive`, `lineSession`, `protocolSession`, `listen`, `Session.expect()` или
  nested pool;
- scenario предоставляет immutable persistent Draft;
- internal settings/plan нормализуют Draft до запуска;
- runtime владеет I/O, lifecycle, shutdown, limits и diagnostics;
- backend-specific types не входят в public signatures.

JDK `ProcessBuilder` остается portability floor. Apache Commons Exec, zt-exec, NuProcess, ExpectIt и Pty4J разрешены в
`:procwright-comparison` как источники проверяемых идей и сравнительный материал, но не как runtime dependencies public
artifacts. Specialized transport может появиться только за узким capability/SPI boundary с теми же public outcomes.

## Инварианты

- Новый public method усиливает конкретный сценарий, а не открывает низкоуровневый backend flag.
- External process types не протекают в core, Kotlin или integrations.
- PTY остается capability session-family сценариев.
- Command-backed tools остаются optional integration layer.
- Comparison/JMH дает research signal, а не machine-independent performance guarantee.

## Последствия

Procwright берет на себя больше runtime-ответственности, чем thin wrapper, но сохраняет единый язык и возможность менять
implementation без смены пользовательской модели.

## Проверка

- `ExternalLibraryBoundaryTest` и `externalLibraryBoundaryCheck` изолируют comparison dependencies.
- `:procwright-comparison:comparisonCheck` проверяет сценарии без изменения public artifacts.
- Public surface tests запрещают backend-specific signatures.
