# Критерии расширения API

Этот документ нужен только как фильтр новых API-решений. Текущую форму вызовов описывает
[scenario-api.md](scenario-api.md), наблюдаемые гарантии — [scenario-contracts.md](scenario-contracts.md), владельцев
инвариантов — [invariant-architecture.md](invariant-architecture.md), а утвержденную поверхность —
[release/public-api-baseline.md](release/public-api-baseline.md).

## Принципы

- **Сначала сценарий.** Новый entry point оправдан отдельным пользовательским намерением и lifecycle, а не новым
  runtime flag.
- **Один dialect.** Расширение продолжает путь `command -> scenario -> persistent Draft -> explicit terminal` и не
  вводит callback-builder, параллельные options objects или shortcuts, скрывающие выбор сценария.
- **Локальная capability.** Настройка появляется только у Draft, где имеет однозначную семантику; общие policy/value
  objects используются лишь при действительно общей модели.
- **Явное ownership.** API, создающий процесс, session, helper или pool, явно передает ответственность за lifecycle.
- **Изоляция state.** Reusable Draft не хранит разделяемое mutable protocol state; session/worker получает собственный
  adapter и runtime owner.
- **Вложенный reuse.** Pool является веткой конкретного reusable session scenario и не раскрывает lease или общий
  process-pool abstraction.
- **Тонкие optional layers.** Kotlin и integrations используют core runtime и не создают второй process
  engine или несовместимую модель конфигурации.
- **Доказуемая польза.** Новый public type/method имеет реальный consumer scenario, единственного владельца инварианта,
  compile-tested external example и behavior proof.

Если изменение не проходит этот фильтр, оно остается internal, optional или не добавляется.
