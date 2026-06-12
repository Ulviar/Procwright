# Политика совместимости

## Базовая runtime-платформа

- Поддерживаемые release targets текущего baseline — Java 17, Java 21 и Java 25.
- Java-код компилируется с `--release ${procwright.javaRelease}`; default target для локальной разработки — 25.
- Kotlin module компилируется с JVM target, соответствующим `procwright.javaRelease`, и остается optional module.
- На Java 21+ runtime Procwright может использовать virtual threads через внутренний runtime boundary. Java 17 variant
  использует daemon platform-thread fallback; это не меняет public API contract, но может менять performance profile.

## Поддержка платформ

Обязательная CI-матрица для публичного релиза:

- Linux latest;
- macOS latest;
- Windows 2025 hosted runner.

Каждая ОС проверяется на Temurin JDK 17, 21 и 25.

Все кроссплатформенные сценарии должны проходить на всех трех платформах. Сценарии, которым нужен POSIX shell или
system PTY provider, skip-аются через JUnit assumptions, если платформа не предоставляет нужную возможность.

Windows ConPTY provider не входит в baseline `0.1.0`. `TerminalPolicy.REQUIRED` должен давать explicit
unsupported behavior, если provider недоступен, и не должен silently fallback в pipes.

## Стабильность публичного API

- Core public API живет в пакетах `io.github.ulviar.procwright`, `io.github.ulviar.procwright.command`,
  `io.github.ulviar.procwright.session`, `io.github.ulviar.procwright.diagnostics`, `io.github.ulviar.procwright.terminal` и
  `io.github.ulviar.procwright.preset`.
- Kotlin ergonomics живет в `io.github.ulviar.procwright.kotlin`.
- CLI-backed integration helpers живут в `io.github.ulviar.procwright.integration`; artifact `:procwright-integrations` является
  именованным Java module `io.github.ulviar.procwright.integrations`.
- Новые public packages требуют отдельного ADR.
- Public top-level package surface покрывается tests, которые сканируют весь production artifact, чтобы случайная утечка
  внутреннего пакета была видна до релиза.
- Точный approved public API surface зафиксирован в [public-api-baseline.md](public-api-baseline.md) и проверяется tests.
- Core artifact является именованным Java module `io.github.ulviar.procwright` и экспортирует только public API packages.
  `io.github.ulviar.procwright.internal` и вложенные runtime-пакеты не экспортируются. Integrations module экспортирует только
  `io.github.ulviar.procwright.integration` и требует core module.
- Для baseline `0.1.0` `Procwright.command(...)` является рекомендуемой точкой входа, `CommandService` остается reusable
  command handle, `SessionOptions.idleTimeout` сохраняет caller-visible semantics, а текущий набор `ScenarioPresets`
  входит в public pre-1.0 API.
- Public API scope `0.1.0` включает сценарии `run`, `interactive`, `expect`, `lineSession`, `protocolSession`,
  `listen`, `lineSession().pooled()` и `protocolSession(factory).pooled()`, как зафиксировано в
  [public-api-baseline.md](public-api-baseline.md). Новые сценарии или изменение их caller-visible invariants требуют
  отдельного ADR и baseline test update.

## Поведенческая совместимость

Behavioral contract важнее случайной реализации. Для релиза должны оставаться зелеными:

- unit tests;
- integration tests;
- Kotlin module tests;
- integrations module tests;
- bounded `stressTest`;
- Javadocs.
- `:procwright-kotlin:kotlinApiDocsCheck` для KDoc покрытия Kotlin public API.

Если поведение меняется, сначала обновляется соответствующий eval в [../evals/process-behavior.md](../evals/process-behavior.md),
затем тесты и только после этого реализация.
