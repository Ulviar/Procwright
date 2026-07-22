# Политика совместимости

## Базовая runtime-платформа

- Публикуемые artifacts имеют Java 17 bytecode target и поддерживаются на runtime JDK 17, 21 и 25.
- Один source tree может компилироваться с `--release ${procwright.javaRelease}` для 17, 21 и 25; default target для
  локальной разработки — 25. Варианты 21/25 являются source-compatibility checks, а не отдельными публикуемыми
  artifacts.
- Kotlin module компилируется Kotlin 2.3.21 с JVM target, соответствующим `procwright.javaRelease`, и остается optional
  module. Consumer compiler должен читать Kotlin 2.3 metadata; совместимость с более старыми compiler versions не
  заявлена.
- На Java 21+ runtime Procwright может использовать virtual threads через внутренний runtime boundary. Java 17 variant
  использует daemon platform-thread fallback; это не меняет public API contract, но может менять performance profile.

## Поддержка платформ

Обязательная CI-матрица для публичного релиза:

- Linux latest;
- macOS latest;
- Windows 2025 hosted runner.

Каждая ячейка матрицы OS/JDK независимо компилирует и проверяет build с Java 17 target на Temurin JDK 17, 21 или 25.
Отдельно на Linux исходники собираются и тестируются с targets 21 и 25 на соответствующих JDK. Такое разделение
доказывает minimum bytecode compatibility, runtime compatibility и source compatibility; compilation и проверки
каждой matrix cell остаются независимыми.

Все кроссплатформенные сценарии должны проходить на всех трех платформах. Сценарии, которым нужен POSIX shell или
system PTY provider, skip-аются через JUnit assumptions, если платформа не предоставляет нужную возможность.

Windows ConPTY provider не входит в baseline `0.1.0`. `TerminalPolicy.REQUIRED` должен давать explicit
unsupported behavior, если provider недоступен, и не должен silently fallback в pipes.

## Стабильность публичного API

- Core public API живет в пакетах `io.github.ulviar.procwright`, `io.github.ulviar.procwright.command`,
  `io.github.ulviar.procwright.session`, `io.github.ulviar.procwright.diagnostics` и `io.github.ulviar.procwright.terminal`.
- Kotlin ergonomics живет в `io.github.ulviar.procwright.kotlin`.
- Protocol adapters живут в `io.github.ulviar.procwright.integration`; artifact `:procwright-integrations` является
  именованным Java module `io.github.ulviar.procwright.integrations`.
- Новые public packages требуют отдельного ADR.
- Public top-level package surface покрывается tests, которые сканируют весь production artifact, чтобы случайная утечка
  внутреннего пакета была видна до релиза.
- Точный approved public API surface зафиксирован в [public-api-baseline.md](public-api-baseline.md) и проверяется tests.
- Core artifact является именованным Java module `io.github.ulviar.procwright` и экспортирует только public API packages.
  `io.github.ulviar.procwright.internal` и вложенные runtime-пакеты не экспортируются. Integrations module экспортирует только
  `io.github.ulviar.procwright.integration` и требует core module.
- Session handles являются sealed interfaces. Их разрешенные реализации остаются недоступными из-за JPMS
  encapsulation и не становятся пользовательским SPI, однако их binary names присутствуют в JVM
  `PermittedSubclasses`. Поэтому exact signature gate фиксирует эту метаинформацию: ее изменение требует осознанного
  compatibility decision и обновления baseline.
- Для baseline `0.1.0` `Procwright.command(...)` является единственной фабрикой `CommandService`. Scenario methods
  возвращают immutable persistent Draft; idle timeout означает caller-visible inactivity в session-family Draft.
- Public API scope `0.1.0` включает сценарии `run`, `interactive`, `expect`, `lineSession`, `protocolSession`,
  `listen`, `lineSession().pooled()` и `protocolSession(factory).pooled()`, как зафиксировано в
  [public-api-baseline.md](public-api-baseline.md). Новые сценарии или изменение их caller-visible invariants требуют
  отдельного ADR и baseline test update.
- Machine-readable baseline считается утвержденным только после того, как он соответствует принятому Draft API и все
  external consumer compilation checks проходят на том же commit. Устаревший baseline нельзя обновлять механически без
  public surface review.

## Поведенческая совместимость

Behavioral contract важнее случайной реализации. Для релиза должны оставаться зелеными:

- unit tests;
- integration tests;
- Kotlin module tests;
- integrations module tests;
- bounded `stressTest`;
- Javadocs;
- `:procwright-kotlin:kotlinApiDocsCheck`, запускающий Dokka parser-backed проверку с
  `reportUndocumented=true` и `failOnWarning=true`;
- Kotlin Gradle Plugin ABI validation относительно tracked baseline.

Если поведение меняется, сначала обновляется соответствующий eval в [../evals/process-behavior.md](../evals/process-behavior.md),
затем тесты и только после этого реализация.
