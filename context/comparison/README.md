# Сравнение библиотек запуска процессов

## Назначение

Этот раздел содержит исследовательский контекст для модуля `:icli-comparison`. Модуль не является runtime dependency
core и не задает публичный API iCLI. Его задача — регулярно проверять, как существующие библиотеки закрывают сценарии,
под которые проектируется iCLI.

## Навигация

- [scenario-catalog.md](scenario-catalog.md) — реальные сценарии и deterministic checks.
- [evaluation-criteria.md](evaluation-criteria.md) — критерии оценки надежности, производительности, API, документации
  и пригодности.
- [test-plan.md](test-plan.md) — масштабирование harness и команды запуска.
- [library-source-notes.md](library-source-notes.md) — источники по внешним библиотекам.
- [subagent-audit-instructions.md](subagent-audit-instructions.md) — инструкции независимым аудиторам.
- [results.md](results.md) — последний локальный прогон comparison harness.

## Команда

```bash
./gradlew :icli-comparison:comparisonReport
```

По умолчанию harness bounded. Для тяжелых прогонов используются system properties, описанные в
[test-plan.md](test-plan.md).
