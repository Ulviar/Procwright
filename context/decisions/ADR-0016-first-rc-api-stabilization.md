# ADR-0016: Public API baseline 0.1.0

## Статус

Принято.

## Контекст

До первой публикации нужно отделить осмысленный пользовательский API от случайной compatibility surface. Baseline
должен сохранять сценарный выбор, явные policies и единственного владельца runtime-инвариантов.

## Решение

- `Procwright.command(...)` создает reusable `CommandService`; пользователь затем выбирает `run`, `interactive`,
  `lineSession`, `protocolSession`, `listen` или вложенный pooled-вариант.
- Convenience shortcuts, скрывающие выбор сценария, не добавляются.
- Каждый scenario method возвращает immutable persistent Draft; `with*` возвращает новый snapshot, а процесс создается
  только `execute()`/`open()`.
- Idle timeout session-family Draft означает caller-visible inactivity.
- `protocolSession` принимает adapter factory, создающую отдельный adapter для каждого session/worker.
- `Session`, `Expect`, `LineSession`, `ProtocolSession`, `StreamSession`, `PooledLineSession` и
  `PooledProtocolSession` являются sealed Procwright-owned handles, а не SPI.
- Diagnostics доставляются асинхронно и best-effort, последовательно для каждого получателя одного lifecycle.
  Pending delivery ограничена; ошибки diagnostic recipients не влияют на процесс.
- Generic async API, raw session pooling, stateful affinity и отдельные Expect/pool diagnostic protocols не входят
  в baseline.

Public JVM signatures фиксируются machine-readable baseline вместе с surface tests, external consumers и
документацией. После публикации изменения подчиняются compatibility policy.

## Последствия

API образует один dialect: команда -> сценарий -> immutable `with*` configuration -> scenario handle/result. Цена этого
решения — отсутствие сверхкоротких shortcuts и невозможность подменять library-owned handles пользовательскими
реализациями.
