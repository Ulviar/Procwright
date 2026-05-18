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

- Core public API живет в пакетах `com.github.ulviar.icli`, `com.github.ulviar.icli.command`,
  `com.github.ulviar.icli.session`, `com.github.ulviar.icli.diagnostics`, `com.github.ulviar.icli.terminal` и
  `com.github.ulviar.icli.preset`.
- Kotlin ergonomics живет в `com.github.ulviar.icli.kotlin`.
- CLI-backed integration helpers живут в `com.github.ulviar.icli.integration`; artifact `:icli-integrations` является
  именованным Java module `com.github.ulviar.icli.integrations`.
- Новые public packages требуют отдельного ADR.
- Public top-level package surface покрывается tests, которые сканируют весь production artifact, чтобы случайная утечка
  внутреннего пакета была видна до релиза.
- Core artifact является именованным Java module `com.github.ulviar.icli` и экспортирует только public API packages.
  `com.github.ulviar.icli.internal` и вложенные runtime-пакеты не экспортируются. Integrations module экспортирует только
  `com.github.ulviar.icli.integration` и требует core module.

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
