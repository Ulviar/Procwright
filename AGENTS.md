# Инструкции для агентов Procwright

## Назначение ветки

Текущий `main` — актуальная основная линия разработки Procwright. Рабочим источником истины являются текущий код, tests,
public docs и документы в `context/`.

## Языковая политика

- Контекстные документы в `context/` пишутся на русском.
- Код, публичные API, комментарии, Javadoc/KDoc, тесты и сообщения коммитов пишутся на английском.
- Ответы пользователю пишутся на языке пользователя, если он не попросил иначе.

## Карта контекста

- [context/README.md](context/README.md) — навигация.
- [context/architecture.md](context/architecture.md) — границы MVP и архитектура.
- [context/invariant-architecture.md](context/invariant-architecture.md) — инварианты API и runtime.
- [context/scenario-api.md](context/scenario-api.md) — сценарный пользовательский API.
- [context/api-ideas.md](context/api-ideas.md) — API-идеи, которые нужно сохранить.
- [context/development-model.md](context/development-model.md) — легкий процесс работы.
- [context/evals/process-behavior.md](context/evals/process-behavior.md) — поведенческие проверки.
- [context/audits/standing-auditor-instructions.md](context/audits/standing-auditor-instructions.md) — постоянные
  роли независимого аудита.
- [context/audits/step-audit-protocol.md](context/audits/step-audit-protocol.md) — протокол пошагового аудита.
- [context/quality/engineering-charter.md](context/quality/engineering-charter.md) — обязательный стандарт качества.
- [context/quality/scorecard.md](context/quality/scorecard.md) — статус и разрывы.

## Принципы

- Любое действие и любой документ должны иметь понятный смысл: адресата, проблему, решение или проверяемую пользу.
  Не делать и не сохранять работу только потому, что "так принято" или "у всех проектов так".
- Сначала маленькое надежное ядро, потом расширения.
- Качество важнее скорости; нельзя экономить на API, инвариантах, тестах и документации.
- Пользователь выбирает сценарий API, а не набор низкоуровневых flags.
- Инварианты должны иметь одного владельца: value object, policy, state machine, validator или runtime component.
  Test/eval доказывает инвариант, но не владеет им.
- Базовые API-идеи проекта ценны: сервис вокруг команды, fluent builder, typed result, sessions.
- Raw session pooling, stateful affinity, real MCP SDK adapter, samples и machine-dependent benchmarks не входят в
  первый MVP. Optional Kotlin module, pooled line-session scenario, protocol integrations и bounded stress suite
  входят как тонкие layers без отдельного process runtime.
- Документация описывает только то, что код доказывает тестами.
- Бесполезную документацию нужно удалять или сжимать до полезного контекста, а не доводить до
  формального совершенства.
- Исторические данные не хранятся в `context/`: закрытые планы, raw reports, audit reports, transcripts,
  одноразовые инструкции и материалы о прошлых итерациях удаляются после переноса полезных выводов в ADR, tests,
  release policy или quality docs. Архивом является Git history.
- Повторяющиеся правила лучше переносить в тесты, валидаторы или ADR.
- Существенный шаг разработки проходит аудит по постоянным ролям из `context/audits/`.
- Release-релевантные изменения должны обновлять `context/release/`, README и release gate checks.

## Рабочий процесс

- Малое изменение: релевантный контекст, код, проверка, короткий отчет.
- Архитектурное решение: ADR в `context/decisions/`.
- Длинный этап: короткий временный план; после завершения удалить его или сжать выводы в ADR/scorecard.
- Документальный шаг: сначала определить аудиторию и пользу документа; если их нет, документ не улучшать, а удалять.
- Рискованное действие: сначала явное подтверждение пользователя.
- Не создавать тяжелые task dossiers по умолчанию.
