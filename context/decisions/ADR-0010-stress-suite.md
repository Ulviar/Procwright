# ADR-0010: Bounded stress suite как release gate

Статус: принято.

## Контекст

One-shot, sessions, streaming, pooling, diagnostics, Kotlin adapters и integration module требуют регрессионных проверок,
которые ловят deadlocks, unbounded retention и lifecycle races под нагрузкой. При этом stress suite не должна превращать
обычную разработку в долгий benchmark run.

## Решение

Добавляем отдельный source set и Gradle task `stressTest`.

`stressTest` входит в `check`, но остается bounded:

- большие stdout/stderr проверяют truncation и отсутствие pipe deadlock;
- timeout churn запускает несколько коротких timeout-supervised процессов параллельно;
- rapid session open/close проверяет lifecycle drain;
- pooled contention проверяет max-size и request accounting;
- PTY stability проверяется повторными `REQUIRED` session launches, когда системный PTY provider доступен;
- memory behavior проверяется через bounded retained output, а не через нестабильные heap assertions.

## Инварианты

- Stress tests не вводят новые runtime features.
- Stress assertions проверяют safety behavior, а не абсолютную производительность конкретной машины.
- PTY stress case должен skip-аться, если системный provider недоступен.
- Любая найденная flake должна превращаться в более точный invariant или отдельное hardening issue, а не в увеличение
  таймаутов без причины.

## Последствия

`./gradlew check` запускает bounded stress suite. Более тяжелые benchmark/JMH сценарии остаются отдельным слоем, чтобы
не смешивать deterministic regressions и machine-dependent measurements.
