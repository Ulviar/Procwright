# План масштабных проверок

## Проверочный harness

Модуль `:icli-comparison` содержит deterministic fixture program и adapter layer для кандидатов. Harness запускается
командой:

```bash
./gradlew :icli-comparison:comparisonReport
```

Результат записывается в `context/comparison/results.md`.

Немутационный release gate:

```bash
./gradlew :icli-comparison:comparisonCheck
```

`comparisonCheck` пишет отчет в `icli-comparison/build/reports/comparison/results.md` и падает, если iCLI теряет
scenario coverage или harness возвращает `FAIL`.

## Масштабирование

Параметры задаются system properties:

- `icli.comparison.iterations` — число повторов latency/reliability сценариев, default `12`.
- `icli.comparison.largeBytes` — размер large stdout/stderr payload, default `4194304`.
- `icli.comparison.timeoutParallelism` — число параллельных timeout runs, default `8`.
- `icli.comparison.timeoutMillis` — timeout hung process scenario, default `80`.

Gradle task `:icli-comparison:comparisonReport` пробрасывает все system properties с префиксом `icli.comparison.` в
forked Java process.

Default values bounded, чтобы test можно было запускать локально часто. Для тяжелого прогона:

```bash
./gradlew :icli-comparison:comparisonReport \
  -Dicli.comparison.iterations=50 \
  -Dicli.comparison.largeBytes=16777216 \
  -Dicli.comparison.timeoutParallelism=32
```

## Сценарные группы

- One-shot reliability: S01-S04.
- Output pressure: S05.
- Timeout churn: S06.
- Streaming: S07.
- Stateful interactions: S08-S09.
- Terminal capability: S10.
- Capability review: S11-S12.

## Статусы report

- `PASS` — сценарий выполнен готовой или специально предназначенной абстракцией кандидата.
- `MANUAL` — сценарий выполнен ручным harness code поверх низкоуровневого API; это важно для оценки API ergonomics.
- `UNSUPPORTED` — кандидат не предоставляет готовую абстракцию в scope сравнения.
- `SKIPPED` — платформа не предоставляет нужную capability для честного прогона.
- `FAIL` — заявленный adapter/scenario не прошел проверку.

## Ограничения

Сравнение не является JMH benchmark. Оно проверяет прикладные workflow и failure modes. Machine-dependent latency
используется только как один сигнал, а не как абсолютная оценка качества.
