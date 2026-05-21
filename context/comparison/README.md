# Сравнение библиотек запуска процессов

## Назначение

Этот раздел содержит исследовательский контекст для модуля `:icli-comparison`. Модуль не является runtime dependency
core и не задает публичный API iCLI. Его задача — регулярно проверять, как существующие библиотеки закрывают сценарии,
под которые проектируется iCLI.

## Навигация

- [scenario-catalog.md](scenario-catalog.md) — реальные сценарии и deterministic checks.
- [evaluation-criteria.md](evaluation-criteria.md) — критерии оценки надежности, производительности, API, документации
  и пригодности.
- [jmh-benchmarks.md](jmh-benchmarks.md) — JMH benchmarks для performance research по сценариям и библиотекам.

## Команда

```bash
./gradlew :icli-comparison:comparisonReport
./gradlew :icli-comparison:comparisonCheck
./gradlew :icli-comparison:stressComparisonReport
./gradlew :icli-comparison:jmhBenchmarkSmoke
./gradlew :icli-comparison:jmhBenchmark
./gradlew :icli-comparison:jmhPtyBenchmark
```

JMH benchmarks запускаются отдельно от release regression gate; команды и методология описаны в
[jmh-benchmarks.md](jmh-benchmarks.md).

Архитектурное решение по итогам сравнения зафиксировано в
[../decisions/ADR-0012-scenario-first-after-library-comparison.md](../decisions/ADR-0012-scenario-first-after-library-comparison.md).

Raw reports, local benchmark results, subagent reports и одноразовые audit outputs не хранятся в `context/`. Локальные
прогоны пишут результаты в `build/`; долгоживущие выводы переносятся в ADR или release/quality docs.
