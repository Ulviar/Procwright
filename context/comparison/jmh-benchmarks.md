# JMH benchmarks

## Назначение

JMH benchmarks дополняют deterministic comparison harness. `comparisonCheck` отвечает за manual research regression
signal; JMH отвечает за performance research и не должен использоваться как pass/fail тест качества API.

`BulkArgumentDraftBenchmark` измеряет bulk append для `CommandSpec` и всех scenario Draft families с 1 000, 10 000 и
20 000 аргументов. Deterministic unit tests проверяют single-snapshot/copy semantics без wall-clock assertions, а этот
benchmark показывает scaling; benchmark numbers не являются release gate.

OpenJDK JMH рекомендует отдельный benchmark subproject или source set, включенный annotation processor и запуск через
готовый JMH runner. Поэтому benchmarks живут в `:procwright-comparison/src/jmh/java`, компилируются отдельным source set и
используют `org.openjdk.jmh.Main`, а не JUnit.

## Команды

Проверить, что JMH sources и generated metadata компилируются:

```bash
./gradlew :procwright-comparison:jmhCompileCheck
```

Проверить wiring коротким smoke-прогоном:

```bash
./gradlew :procwright-comparison:jmhBenchmarkSmoke
```

Запустить полный non-PTY suite:

```bash
./gradlew :procwright-comparison:jmhBenchmark
```

Запустить optional PTY suite, если платформа предоставляет PTY capability:

```bash
./gradlew :procwright-comparison:jmhPtyBenchmark
```

Проверить GC-profiler wiring коротким allocation smoke-прогоном:

```bash
./gradlew :procwright-comparison:jmhMemoryBenchmarkSmoke
```

Собрать allocation rate и bytes/op для representative one-shot, large-output, streaming и line-session сценариев:

```bash
./gradlew :procwright-comparison:jmhMemoryBenchmark
```

Результаты пишутся в `procwright-comparison/build/reports/jmh/*.json`.

## Сценарии

- `OneShotProcessBenchmark` — success, stdin echo, bounded large stdout и timeout для Procwright, JDK ProcessBuilder, Apache
  Commons Exec, ZeroTurnaround zt-exec и NuProcess.
- `OneShotCaptureAllocationBenchmark` — изолированное накопление bounded output без process startup: текущий chunk
  accumulator сравнивается с прежней формой на `ByteArrayOutputStream`; обязательное defensive copy публичного
  `CommandResult.stdoutBytes()` измеряется отдельным benchmark method и не смешивается с capture construction.
- `StreamingProcessBenchmark` — stdout/stderr streaming callback path для тех же кандидатов.
- `LineSessionProcessBenchmark` — two-request line session для Procwright и manual JDK harness.
- `ExpectProcessBenchmark` — prompt automation для Procwright, manual JDK harness и ExpectIt.
- `ExpectMatchBufferBenchmark` — large unmatched stdout, поступающий малыми chunks, для защиты линейного hot path
  bounded match buffer.
- `ExpectNearMatchProcessBenchmark` — reusable test CLI process с paced 512 KiB near-match rounds; включает decode,
  monitor/wakeup, chunk publication и matching, но выносит process startup в trial setup.
- `PooledProcessBenchmark` — pool lifecycle, warmup, два request и cleanup как Procwright-only macro-сценарий; это не
  steady-state latency уже прогретого pool.
- `ToolObservationBenchmark` — structured command-backed tool observation как Procwright integration сценарий.
- `PtyProcessBenchmark` — optional terminal probe для Procwright PTY provider и Pty4J.

## Методология

- Process-backed comparison benchmarks измеряют macro/workflow latency внешнего процесса. Standalone internal
  benchmarks изолируют конкретные bounded structures или framing/capture paths без process startup.
- Memory tasks используют встроенный JMH GC profiler. Основные сигналы — allocation rate и normalized bytes/op;
  process RSS и абсолютный heap size не используются как стабильный regression assertion.
- Capture allocation benchmark использует одинаковый уже созданный input для обеих реализаций и проверяет их
  семантическую эквивалентность в setup. Вывод об улучшении допустим только после отдельного smoke/full запуска с GC
  profiler; наличие новой структуры само по себе не считается измеренным performance result.
- Benchmarks на общем `JmhSettings` получают `@Fork(2)`, warmup и measurement iterations из base class. Standalone
  internal benchmarks объявляют эквивалентные fork/warmup/measurement annotations на своих классах; smoke task
  намеренно переопределяет их вниз только для проверки wiring.
- Macro benchmarks запускают реальный fixture process и проверяют семантический outcome. `ExpectMatchBufferBenchmark`
  изолирует внутреннюю bounded structure, чтобы process startup не скрывал algorithmic regression.
- `Blackhole` потребляет статус, output sizes, flags и notes, чтобы outcome оставался observable для JIT.
- `@Threads(1)` используется намеренно: параллельный запуск внешних процессов измеряет scheduler/resource contention, а
  не latency одного workflow.
- Unsupported combinations не включаются в `@Param`; optional PTY benchmark вынесен в отдельную task.
- `comparisonCheck` остается ручным источником pass/fail reliability для research context. JMH numbers требуют отдельной
  интерпретации и не должны автоматически становиться release blocker без ADR.
- `:procwright-comparison:check` не запускает comparison/JMH tasks; они вызываются явно, когда нужен research-сигнал.
