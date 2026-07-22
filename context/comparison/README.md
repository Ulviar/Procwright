# Сравнение библиотек запуска процессов

## Назначение

Этот раздел содержит исследовательский контекст для модуля `:procwright-comparison`. Модуль не является runtime dependency
core и не задает публичный API Procwright. Его задача — регулярно проверять, как существующие библиотеки закрывают сценарии,
под которые проектируется Procwright.

## Навигация

- [scenario-catalog.md](scenario-catalog.md) — реальные сценарии и deterministic checks.
- [evaluation-criteria.md](evaluation-criteria.md) — критерии оценки надежности, производительности, API, документации
  и пригодности.
- [jmh-benchmarks.md](jmh-benchmarks.md) — JMH benchmarks для performance research по сценариям и библиотекам.

## Команда

```bash
./gradlew :procwright-comparison:comparisonReport
./gradlew :procwright-comparison:comparisonCheck
./gradlew :procwright-comparison:stressComparisonReport
./gradlew :procwright-comparison:jmhBenchmarkSmoke
./gradlew :procwright-comparison:jmhBenchmark
./gradlew :procwright-comparison:jmhPtyBenchmark
```

JMH benchmarks запускаются отдельно от release regression gate; команды и методология описаны в
[jmh-benchmarks.md](jmh-benchmarks.md).

Все Gradle-входы для запуска сравнений пишут только в `procwright-comparison/build/reports/comparison/`:
`run` — в `application-results.md`, `comparisonReport` — в `results.md`, `comparisonCheck` — в
`verification-results.md`, `stressComparisonReport` — в `stress-results.md`. Они не изменяют tracked context.

Архитектурное решение по итогам сравнения зафиксировано в
[../decisions/ADR-0012-scenario-first-after-library-comparison.md](../decisions/ADR-0012-scenario-first-after-library-comparison.md).

Raw reports, local benchmark results, subagent reports и одноразовые audit outputs не хранятся в `context/`. Локальные
прогоны пишут результаты в `build/`; долгоживущие выводы переносятся в ADR или release/quality docs.
