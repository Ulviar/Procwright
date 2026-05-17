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
- [jmh-benchmarks.md](jmh-benchmarks.md) — JMH benchmarks для performance research по сценариям и библиотекам.
- [library-source-notes.md](library-source-notes.md) — источники по внешним библиотекам.
- [qualitative-assessment.md](qualitative-assessment.md) — качественная оценка API/task-fit/maintenance выводов.
- [subagent-audit-instructions.md](subagent-audit-instructions.md) — инструкции независимым аудиторам.
- [results.md](results.md) — последний локальный прогон comparison harness.

## Команда

```bash
./gradlew :icli-comparison:comparisonReport
./gradlew :icli-comparison:comparisonCheck
./gradlew :icli-comparison:jmhBenchmarkSmoke
```

По умолчанию harness bounded. Для тяжелых прогонов используются system properties, описанные в
[test-plan.md](test-plan.md).

JMH benchmarks запускаются отдельно от release regression gate; команды и методология описаны в
[jmh-benchmarks.md](jmh-benchmarks.md).

Архитектурное решение по итогам сравнения зафиксировано в
[../decisions/ADR-0012-scenario-first-after-library-comparison.md](../decisions/ADR-0012-scenario-first-after-library-comparison.md).
