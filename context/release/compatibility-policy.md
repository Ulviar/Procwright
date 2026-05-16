# Политика совместимости

## Базовая runtime-платформа

- Минимальная платформа clean rewrite — JDK 25.
- Java-код компилируется с `--release 25`.
- Kotlin module компилируется с JVM target 25 и остается optional module.

## Поддержка платформ

Обязательная CI-матрица для release candidate:

- Linux latest;
- macOS latest;
- Windows latest.

Все кроссплатформенные сценарии должны проходить на всех трех платформах. Сценарии, которым нужен POSIX shell или
system PTY provider, skip-аются через JUnit assumptions, если платформа не предоставляет нужную возможность.

## Стабильность публичного API

- Core public API живет в `com.github.ulviar.icli`.
- Kotlin ergonomics живет в `com.github.ulviar.icli.kotlin`.
- CLI-backed integration helpers живут в `com.github.ulviar.icli.integration`.
- Новые public packages требуют отдельного ADR.
- Public top-level package surface покрывается tests, которые сканируют весь production artifact, чтобы случайная утечка
  внутреннего пакета была видна до релиза.

## Поведенческая совместимость

Behavioral contract важнее случайной реализации. Для релиза должны оставаться зелеными:

- unit tests;
- integration tests;
- Kotlin module tests;
- integrations module tests;
- bounded `stressTest`;
- Javadocs.
- `:icli-kotlin:kotlinApiDocsCheck` для KDoc покрытия Kotlin public API.

Если поведение меняется, сначала обновляется соответствующий eval в [../evals/process-behavior.md](../evals/process-behavior.md),
затем тесты и только после этого реализация.
