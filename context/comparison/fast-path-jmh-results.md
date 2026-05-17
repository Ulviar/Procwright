# JMH после default fast paths

## Прогон

- Дата: 2026-05-17.
- Code commit: `d2e6ce4` (`Add default runtime fast paths`).
- Baseline report: [jmh-results.md](jmh-results.md), кодовый baseline `79a2d18`.
- Команды:
  - `./gradlew :icli-comparison:jmhBenchmark`
  - `./gradlew :icli-comparison:jmhPtyBenchmark`
- Results:
  - `icli-comparison/build/reports/jmh/results.json`
  - `icli-comparison/build/reports/jmh/pty-results.json`
- Окружение: Darwin 25.3.0 arm64, OpenJDK 26.0.1+8, JMH 1.37.

## Что изменилось

Оптимизации были намеренно внутренними и не меняли public API:

- `Diagnostics` получил disabled fast path для `DiagnosticsOptions.defaults()`: no-op diagnostics больше не создают
  `DiagnosticEvent`, `UUID`, `Instant` и virtual threads на каждый event.
- Call sites, которые строят `CommandEcho` только для diagnostics, перешли на lazy supplier. При disabled diagnostics
  echo не строится, но `scenario` валидируется как metadata-инвариант.
- `ProcessKernel` больше не отправляет default `StdinPolicy.CLOSED` через отдельный executor task. Обычный one-shot run
  закрывает stdin синхронно; реальный `INPUT` path остался async, чтобы timeout не блокировался на pipe write.

## Проверки и аудит

- `./gradlew :test --tests com.github.ulviar.icli.DiagnosticsOptionsTest`
- `./gradlew integrationTest --tests com.github.ulviar.icli.OneShotExecutionIntegrationTest --tests com.github.ulviar.icli.DiagnosticsIntegrationTest`
- `./gradlew check`
- Два независимых аудита проверили scenario-first/API целостность и concurrency/lifecycle path. Первичное замечание про
  validation fast path исправлено; повторный аудит не нашел blockers/P2/P3.

## Методологическое ограничение

Текущий JMH suite измеряет macro/workflow latency внешнего процесса. Цена запуска fixture JVM и OS scheduling в этих
сценариях больше, чем ожидаемый выигрыш от пары внутренних fast paths. Поэтому отсутствие заметного улучшения в таблицах
ниже не означает, что fast paths бесполезны; это означает, что текущий macro-suite не является хорошим инструментом для
измерения такой малой внутренней экономии.

Сравнение с baseline также не является чистым A/B: внешние кандидаты в этом прогоне тоже заметно сдвинулись. Например,
`large stdout bounded capture` ухудшился не только у iCLI, но и у JDK ProcessBuilder, zt-exec и NuProcess. Значит,
часть дельты — шум машины и текущего состояния окружения.

## iCLI: baseline vs current

Меньше — лучше. `Delta` положительный, если текущий прогон медленнее baseline.

| Scenario | Baseline, ms/op | Current, ms/op | Delta | Change |
|---|---:|---:|---:|---:|
| prompt automation | 49.012 | 48.561 | -0.451 | -0.9% |
| two-request line session | 46.617 | 48.108 | +1.491 | +3.2% |
| large stdout bounded capture | 44.678 | 49.770 | +5.092 | +11.4% |
| stdin echo | 48.539 | 48.199 | -0.340 | -0.7% |
| success | 45.118 | 47.180 | +2.062 | +4.6% |
| timeout | 74.654 | 74.034 | -0.620 | -0.8% |
| pool lifecycle with warmup and two requests | 47.603 | 48.932 | +1.329 | +2.8% |
| stdout/stderr callbacks | 147.293 | 148.398 | +1.105 | +0.7% |
| command-backed tool success and failure | 92.038 | 95.669 | +3.631 | +3.9% |
| terminal probe | 15.605 | 15.641 | +0.036 | +0.2% |

## Same-run relative signal

Сравнение с JDK ProcessBuilder в том же прогоне полезнее абсолютного сравнения с предыдущим днем/минутой, потому что
оно частично учитывает шум окружения.

| Scenario | Baseline iCLI - JDK | Current iCLI - JDK | Interpretation |
|---|---:|---:|---|
| prompt automation | +0.165 ms | -4.425 ms | JDK candidate был шумнее в новом прогоне; не считать устойчивым выигрышем iCLI. |
| two-request line session | +1.243 ms | +0.832 ms | Практически близко. |
| large stdout bounded capture | +1.294 ms | +1.828 ms | Без улучшения; текущий прогон шумный у всех кандидатов. |
| stdin echo | +1.239 ms | +0.012 ms | iCLI стал равен JDK в этом прогоне, но input path почти не затрагивался оптимизацией. |
| success | -0.995 ms | -0.006 ms | iCLI и JDK равны в новом прогоне. |
| timeout | +6.507 ms | +6.031 ms | Разрыв сохранился; эти fast paths не решают timeout orchestration. |
| stdout/stderr callbacks | -1.517 ms | -1.135 ms | Практически тот же уровень. |

## Вывод

Заметного macro-latency улучшения JMH не показал. Оптимизации стоит оставить, потому что они:

- убирают реальную лишнюю работу на default path;
- не расширяют public API;
- сохраняют diagnostics semantics при enabled listener/sink;
- сохраняют async stdin write только там, где он нужен для safety under timeout;
- прошли targeted tests, full `check` и независимый аудит.

Но эти изменения не решают главные performance-вопросы, выявленные сравнением:

- timeout path по-прежнему примерно на `6-8 ms/op` медленнее JDK/zt-exec/NuProcess в текущем fixture;
- PTY path по-прежнему ограничен `script(1)` provider; raw Pty4J быстрее по point estimate, но текущие интервалы
  пересекаются, поэтому этот сигнал требует повторных прогонов;
- текущий JMH suite недостаточно чувствителен к micro-overhead diagnostics/default stdin.

## Следующие performance-шаги

1. Добавить отдельный JMH microbenchmark для `Diagnostics.emit` disabled/enabled paths, чтобы измерять именно этот слой.
2. Добавить lightweight non-JVM fixture для timeout path, чтобы отделить shutdown orchestration от стоимости запуска JVM.
3. Добавить steady-state pool benchmark, где pool создается в `@Setup`, а measurement покрывает только request/response.
4. Рассматривать PTY performance через optional provider module, а не через расширение core API.
