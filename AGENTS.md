# Инструкции для агентов iCLI rewrite

## Назначение ветки

Эта ветка — чистый перезапуск iCLI. Старый код, samples, task dossiers и старая архитектура не являются базой
для инкрементального рефакторинга.

Старую реализацию можно смотреть в истории `main`, исходная точка:
`89c8be60541df2c4aa82b1d7a136a928ff699188`.

## Языковая политика

- Контекстные документы в `context/` пишутся на русском.
- Код, публичные API, комментарии, Javadoc/KDoc, тесты и сообщения коммитов пишутся на английском.
- Ответы пользователю пишутся на языке пользователя, если он не попросил иначе.

## Карта контекста

- [context/README.md](context/README.md) — навигация.
- [context/architecture.md](context/architecture.md) — границы MVP и архитектура.
- [context/api-ideas.md](context/api-ideas.md) — API-идеи, которые нужно сохранить.
- [context/legacy-lessons.md](context/legacy-lessons.md) — выводы из старой версии.
- [context/development-model.md](context/development-model.md) — легкий процесс работы.
- [context/evals/process-behavior.md](context/evals/process-behavior.md) — поведенческие проверки.
- [context/quality/scorecard.md](context/quality/scorecard.md) — статус и разрывы.

## Принципы

- Сначала маленькое надежное ядро, потом расширения.
- API-идеи старого проекта ценны: сервис вокруг команды, fluent builder, typed result, sessions.
- Старую реализацию не копировать механически.
- Pooling, MCP, samples, benchmarks и Kotlin Flow не входят в первый MVP.
- Документация описывает только то, что код доказывает тестами.
- Повторяющиеся правила лучше переносить в тесты, валидаторы или ADR.

## Рабочий процесс

- Малое изменение: релевантный контекст, код, проверка, короткий отчет.
- Архитектурное решение: ADR в `context/decisions/`.
- Длинный этап: короткий план в `context/plans/active/`.
- Рискованное действие: сначала явное подтверждение пользователя.
- Не создавать тяжелые task dossiers по умолчанию.
