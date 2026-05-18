# Итоги JMH-прогона

## Прогон

- Дата: 2026-05-17.
- Commit: `79a2d18` (`Add JMH comparison benchmarks`).
- Команды:
  - `./gradlew :icli-comparison:jmhBenchmark`
  - `./gradlew :icli-comparison:jmhPtyBenchmark`
- Results:
  - `icli-comparison/build/reports/jmh/results.json`
  - `icli-comparison/build/reports/jmh/pty-results.json`
- Окружение: Darwin 25.3.0 arm64, OpenJDK 26.0.1+8, JMH 1.37.
- Режим: `AverageTime`, `ms/op`, `@Fork(2)`, warmup `3 x 1 s`, measurement `5 x 1 s`, `@Threads(1)`,
  JMH iteration timeout `15 s`.

## Правила интерпретации

JMH-прогон измеряет macro/workflow latency реального внешнего процесса. Это не изолированная цена Java API и не
микробенчмарк чистого CPU-кода. Для сценариев с одноразовым запуском большая часть результата — цена запуска процесса,
загрузки fixture JVM и IO вокруг него.

Меньше — лучше. `Error` в таблицах ниже — JMH-reported half-width confidence interval для итогового score; в консольном
выводе JMH помечает его как `±(99.9%)`. Числа с пересекающимися интервалами стоит считать практически близкими до
дополнительных повторных прогонов на выделенной машине.

Эти benchmarks остаются исследовательским инструментом. Release regression signal по надежности по-прежнему дает
`comparisonCheck`; JMH не должен становиться pass/fail gate без отдельного ADR.

JMH iteration timeout `15 s` ограничивает зависший benchmark invocation. Timeout-сценарий внутри
`OneShotProcessBenchmark.timeout` использует отдельный process timeout `60 ms` для fixture-команды `sleep 1000`.

## Короткие выводы

- В сценариях one-shot success, line session, expect automation и streaming iCLI находится рядом с manual JDK harness и
  профильными библиотеками. Наблюдаемая разница обычно мала относительно природы macro-сценария.
- Timeout path выглядит главным кандидатом на дальнейшее исследование: iCLI показал `74.654 ms/op`, тогда как JDK,
  zt-exec и NuProcess лежат в диапазоне `66.246-68.147 ms/op`. Commons Exec находится рядом с iCLI.
- NuProcess быстрее в нескольких one-shot сценариях. Первичный JDK 26 прогон показывал native-access warning через JNA;
  после отчета comparison/JMH tasks получили explicit JVM flags для native/Unsafe access, чтобы research harness не
  шумел предупреждениями внешних библиотек.
- PTY probe у raw Pty4J быстрее (`11.501 ms/op`) iCLI provider path (`15.605 ms/op`). Это ожидаемый сигнал к проверке
  adapter/lifecycle overhead, но оптимизация не должна ломать сценарный API и cleanup guarantees.
- `PooledProcessBenchmark` и `ToolObservationBenchmark` являются iCLI-only baselines. Их нельзя ранжировать против
  внешних библиотек; они фиксируют стоимость собственных интеграционных сценариев.

## One-shot scenarios

| Scenario | Candidate | Score, ms/op | Error |
|---|---:|---:|---:|
| large stdout bounded capture | nuprocess | 43.187 | 0.837 |
| large stdout bounded capture | jdk-process-builder | 43.384 | 1.592 |
| large stdout bounded capture | icli | 44.678 | 1.732 |
| large stdout bounded capture | zt-exec | 45.122 | 0.822 |
| large stdout bounded capture | commons-exec | 46.293 | 4.099 |
| stdin echo | nuprocess | 44.234 | 0.514 |
| stdin echo | jdk-process-builder | 47.300 | 0.845 |
| stdin echo | zt-exec | 47.541 | 1.150 |
| stdin echo | commons-exec | 48.082 | 0.909 |
| stdin echo | icli | 48.539 | 4.700 |
| success | nuprocess | 42.843 | 0.756 |
| success | icli | 45.118 | 1.200 |
| success | zt-exec | 45.257 | 0.980 |
| success | commons-exec | 46.009 | 1.409 |
| success | jdk-process-builder | 46.113 | 0.740 |
| timeout | nuprocess | 66.246 | 0.515 |
| timeout | zt-exec | 67.375 | 0.600 |
| timeout | jdk-process-builder | 68.147 | 0.556 |
| timeout | icli | 74.654 | 1.730 |
| timeout | commons-exec | 74.989 | 1.618 |

## Session and automation scenarios

| Scenario | Candidate | Score, ms/op | Error |
|---|---:|---:|---:|
| prompt automation | expectit | 46.554 | 2.797 |
| prompt automation | jdk-process-builder | 48.847 | 2.392 |
| prompt automation | icli | 49.012 | 1.385 |
| two-request line session | jdk-process-builder | 45.374 | 1.773 |
| two-request line session | icli | 46.617 | 3.436 |

## Streaming scenario

| Scenario | Candidate | Score, ms/op | Error |
|---|---:|---:|---:|
| stdout/stderr callbacks | icli | 147.293 | 2.320 |
| stdout/stderr callbacks | nuprocess | 147.465 | 2.940 |
| stdout/stderr callbacks | jdk-process-builder | 148.810 | 1.629 |
| stdout/stderr callbacks | zt-exec | 149.378 | 1.606 |
| stdout/stderr callbacks | commons-exec | 149.707 | 1.177 |

## iCLI-only baselines

| Scenario | Candidate | Score, ms/op | Error |
|---|---:|---:|---:|
| pool lifecycle with warmup and two requests | N/A | 47.603 | 2.454 |
| command-backed tool success and failure | N/A | 92.038 | 6.390 |

## PTY scenario

| Scenario | Candidate | Score, ms/op | Error |
|---|---:|---:|---:|
| terminal probe | pty4j | 11.501 | 0.796 |
| terminal probe | icli | 15.605 | 1.925 |

## Наблюдения по предупреждениям

Эти предупреждения были видны в console output первичного Gradle/JMH run. JSON-артефакты JMH сохраняют численные
результаты, но не сохраняют stdout/stderr предупреждений runner и native dependencies.

- JMH 1.37 на JDK 26 печатал предупреждение о terminally deprecated `sun.misc.Unsafe::objectFieldOffset`; JMH tasks
  теперь запускаются с `--sun-misc-unsafe-memory-access=allow`.
- NuProcess и Pty4J через JNA печатали restricted native-access warning; comparison/JMH tasks теперь запускаются с
  `--enable-native-access=ALL-UNNAMED`.
- zt-exec и Pty4J печатали SLF4J no-provider warning; comparison module теперь содержит локальный no-op SLF4J provider,
  чтобы не тянуть отдельный logging backend в research harness.
- JMH сообщает, что на текущей JVM используются experimental compiler blackholes. Поэтому абсолютные значения стоит
  подтверждать повторными прогонами перед архитектурными решениями, чувствительными к нескольким миллисекундам.

## Рекомендации

1. Сохранить текущие JMH benchmarks как research suite, отделенный от release gates.
2. Для timeout path добавить follow-up benchmark с легковесным non-JVM fixture, чтобы отделить цену timeout orchestration
   от цены запуска JVM fixture.
3. Добавить steady-state pool benchmark, где pool создается в `@Setup` и measurement покрывает только request/response
   path. Текущий benchmark намеренно измеряет lifecycle macro-сценарий.
4. Для PTY path проверить adapter overhead внутри iCLI provider, но не переносить raw Pty4J semantics в публичный API.
5. Перед любыми performance-driven изменениями повторить прогон на тихой машине и сравнить raw JMH JSON между минимум
   тремя запусками.
