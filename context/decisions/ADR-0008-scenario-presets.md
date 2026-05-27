# ADR-0008: Сценарные presets как типизированные настройки builder

## Статус

Принято.

## Контекст

Presets должны закрывать распространенные задачи, но не должны создавать новые подсистемы или отдельные runners.
Пользователь сначала выбирает сценарий (`run`, `listen`, `lineSession`, `interactive`, `pooled`), затем при
необходимости применяет preset как пакет осмысленных переопределений policy.

## Решение

Добавить `ScenarioPresets` — stateless public utility с typed `Consumer<...>` customizers для существующих invocation
builders.

Текущий набор:

- `commandAutomation(...)` для bounded one-shot automation;
- `environmentDiagnostics(...)` для merged UTF-8 diagnostics output;
- `binaryOutputCapture(...)` для bounded binary-oriented capture с доступом к byte snapshots в `CommandResult`;
- `replLineMode(...)` для line-oriented REPL lifecycle defaults;
- `promptAutomationSession(...)` для raw session, которую caller оборачивает в `Expect`;
- `logFollowing(...)` для listen-only log following;
- `terminalRequiredSession(...)` для terminal-required interactive session;
- `warmWorkerPool(...)` для pooled line workers.

Presets не запускают процессы, не хранят state, не создают execution plans и не обходят resolver. Они только применяют
typed overrides к уже выбранному scenario builder.

## Инварианты

- preset не является runner;
- preset не выбирает сценарий вместо пользователя;
- preset не должен добавлять options, которых нет в соответствующем scenario builder;
- preset validation происходит при создании preset, а domain validation остается в builder/options objects;
- пользователь может комбинировать preset с явными overrides в обычной builder lambda.

## Последствия

Плюсы:

- API получает более широкий язык намерений без расширения runtime;
- common workflows становятся короче и compile-tested;
- presets остаются composable Java-first API и легко вызываются из Kotlin.

Минусы:

- порядок применения preset и явных overrides остается обычным порядком fluent builder calls;
- `binaryOutputCapture(...)` остается bounded capture preset; streaming bytes-first workflow остается отдельной будущей
  задачей;
- слишком много presets может превратиться в каталог use cases, поэтому новые presets должны проходить eval/ADR.
