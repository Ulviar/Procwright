# JMH benchmarks

## Назначение

JMH benchmarks дополняют deterministic comparison harness. `comparisonCheck` отвечает за manual research regression
signal; JMH отвечает за performance research и не должен использоваться как pass/fail тест качества API.

OpenJDK JMH рекомендует отдельный benchmark subproject или source set, включенный annotation processor и запуск через
готовый JMH runner. Поэтому benchmarks живут в `:icli-comparison/src/jmh/java`, компилируются отдельным source set и
используют `org.openjdk.jmh.Main`, а не JUnit.

## Команды

Проверить, что JMH sources и generated metadata компилируются:

```bash
./gradlew :icli-comparison:jmhCompileCheck
```

Проверить wiring коротким smoke-прогоном:

```bash
./gradlew :icli-comparison:jmhBenchmarkSmoke
```

Запустить полный non-PTY suite:

```bash
./gradlew :icli-comparison:jmhBenchmark
```

Запустить optional PTY suite, если платформа предоставляет PTY capability:

```bash
./gradlew :icli-comparison:jmhPtyBenchmark
```

Результаты пишутся в `icli-comparison/build/reports/jmh/*.json`.

## Сценарии

- `OneShotProcessBenchmark` — success, stdin echo, bounded large stdout и timeout для iCLI, JDK ProcessBuilder, Apache
  Commons Exec, ZeroTurnaround zt-exec и NuProcess.
- `StreamingProcessBenchmark` — stdout/stderr streaming callback path для тех же кандидатов.
- `LineSessionProcessBenchmark` — two-request line session для iCLI и manual JDK harness.
- `ExpectProcessBenchmark` — prompt automation для iCLI, manual JDK harness и ExpectIt.
- `PooledProcessBenchmark` — pool lifecycle, warmup, два request и cleanup как iCLI-only macro-сценарий; это не
  steady-state latency уже прогретого pool.
- `ToolObservationBenchmark` — structured command-backed tool observation как iCLI integration сценарий.
- `PtyProcessBenchmark` — optional terminal probe для iCLI PTY provider и Pty4J.

## Методология

- Benchmarks измеряют macro/workflow latency внешнего процесса, а не наносекундный CPU loop.
- `@Fork(2)`, warmup и measurement iterations заданы на уровне base class; smoke task намеренно переопределяет их вниз
  только для проверки wiring.
- Каждый invocation запускает реальный fixture process и проверяет семантический результат. Некорректный outcome падает
  как failed benchmark, а не попадает в numbers.
- `Blackhole` потребляет статус, output sizes, flags и notes, чтобы outcome оставался observable для JIT.
- `@Threads(1)` используется намеренно: параллельный запуск внешних процессов измеряет scheduler/resource contention, а
  не latency одного workflow.
- Unsupported combinations не включаются в `@Param`; optional PTY benchmark вынесен в отдельную task.
- `comparisonCheck` остается ручным источником pass/fail reliability для research context. JMH numbers требуют отдельной
  интерпретации и не должны автоматически становиться release blocker без ADR.
- `:icli-comparison:check` не запускает `comparisonCheck`, `jmhCompileCheck`, `jmhBenchmarkSmoke`, `jmhBenchmark` или
  `jmhPtyBenchmark`; эти задачи вызываются явно, когда нужен research-сигнал.
