# ADR-0008: Presets как преобразования scenario Draft

## Статус

Принято.

## Контекст

Повторяющиеся сочетания policy должны сокращаться без новых runners, lifecycle и callback-based configuration dialect.

## Решение

`ScenarioPresets` — stateless utility с typed функциями вида `SpecificDraft -> SpecificDraft`. Preset принимает уже
выбранный Draft, применяет доступные ему `with*` и возвращает новый immutable Draft.

Поддерживаемые transformations:

- `commandAutomation`;
- `environmentDiagnostics`;
- `binaryOutputCapture`;
- `replLineMode`;
- `promptAutomationSession`;
- `logFollowing`;
- `terminalRequiredSession`.

Preset не выбирает сценарий, не хранит callbacks/state, не запускает процесс и не создает internal plan. Его параметры
валидируются до возврата нового Draft.

## Инварианты

- исходный Draft не меняется;
- порядок композиции виден в обычном Java-коде;
- preset не получает настройку, которой нет в конкретном Draft;
- новый preset требует реального повторяющегося workflow и compile-tested example.

## Последствия

Presets расширяют язык намерений без нового runtime. Их число ограничивается полезными комбинациями, а не попыткой
назвать каждый набор параметров.
