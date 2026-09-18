# Политика совместимости

## Базовая runtime-платформа

- Публикуемые artifacts имеют Java 17 bytecode target и поддерживаются на runtime JDK 17, 21 и 25.
- Один source tree может компилироваться с `--release ${procwright.javaRelease}` для 17, 21 и 25; default target для
  локальной разработки — 25. Варианты 21/25 являются source-compatibility checks, а не отдельными публикуемыми
  artifacts.
- Kotlin module компилируется Kotlin 2.4.0 с JVM target, соответствующим `procwright.javaRelease`, и остается optional
  module. Consumer compiler должен читать Kotlin 2.4 metadata; совместимость с более старыми compiler versions не
  заявлена.
- На Java 24+ runtime Procwright может использовать virtual threads через внутренний runtime boundary. Java 17–23
  используют daemon platform-thread fallback, чтобы monitor pinning в ранней реализации virtual threads не нарушал
  bounded concurrency; это не меняет public API contract, но может менять performance profile.

## Поддержка платформ

Обязательная CI-матрица для публичного релиза:

- Linux latest;
- macOS latest;
- Windows 2025 hosted runner.

Java 17-targeted build проверяется на Temurin JDK 17 под Linux, macOS и Windows, а также на Temurin JDK 21/25 под Linux.
Отдельно на Linux исходники собираются и тестируются с targets 21 и 25 на соответствующих JDK. Такое разделение
проверяет minimum bytecode compatibility, три основные OS на минимальной JDK, новые runtimes и source compatibility,
не заявляя полный Cartesian product OS × JDK.

Все кроссплатформенные сценарии должны проходить на всех трех платформах. Сценарии, которым нужен POSIX shell или
system PTY provider, skip-аются через JUnit assumptions, если платформа не предоставляет нужную возможность.

Windows ConPTY provider не входит в планируемый первый выпуск. `TerminalPolicy.REQUIRED` должен давать explicit
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
- Намеренная public API boundary зафиксирована в [public-api-boundary.md](public-api-boundary.md) и проверяется surface
  tests и external consumers.
- Core artifact является именованным Java module `io.github.ulviar.procwright` и экспортирует только public API packages.
  `io.github.ulviar.procwright.internal` и вложенные runtime-пакеты не экспортируются. Integrations module экспортирует только
  `io.github.ulviar.procwright.integration` и требует core module.
- Session handles являются sealed interfaces. Их разрешенные реализации остаются недоступными из-за JPMS
  encapsulation и не становятся пользовательским SPI. После первого выпуска binary compatibility gate должен учитывать
  их JVM `PermittedSubclasses`.
- Планируемый public API scope и scenario grammar зафиксированы в
  [public-api-boundary.md](public-api-boundary.md). Поведение принадлежит
  [scenario-contracts.md](../scenario-contracts.md). Новые сценарии или изменение caller-visible invariants требуют
  отдельного ADR и обновления surface tests/consumers.
- До первого выпуска точные JVM signatures можно менять осознанно вместе с surface tests, external consumers и public
  docs.

После публикации `0.1.0`, до первого следующего изменения public API:

- задача `javaBinaryCompatibilityCheck` на базе `japicmp` сравнивает текущие `procwright` и
  `procwright-integrations` с `io.github.ulviar:procwright:0.1.0` и
  `io.github.ulviar:procwright-integrations:0.1.0`;
- `japicmp` проверяет public/protected bytecode API, hierarchy, generic signatures и checked exceptions;
  `SessionContractShapeTest` остаётся release-locked владельцем `PermittedSubclasses`, а module descriptor tests —
  владельцем JPMS exports/requires;
- `quickCheck`, CI и publication-readiness зависят от этой задачи;
- `procwright-kotlin` продолжает использовать стандартный Kotlin ABI gate; baseline для него фиксируется на release
  tag `0.1.0`;
- следующий release становится новым baseline только после осознанного SemVer/compatibility decision.

До выполнения bootstrap public API после `0.1.0` не меняется.

## Поведенческая совместимость

Behavioral contract важнее случайной реализации. Для релиза должны оставаться зелеными:

- unit tests;
- integration tests;
- Kotlin module tests;
- integrations module tests;
- bounded `stressTest`;
- Javadocs;
- `:procwright-kotlin:javadocJar`, запускающий Dokka-проверку с
  `reportUndocumented=true` и `failOnWarning=true`;
- Kotlin Gradle Plugin ABI validation относительно tracked baseline.

Если поведение меняется, сначала обновляется соответствующий eval в [../evals/process-behavior.md](../evals/process-behavior.md),
затем тесты и только после этого реализация.
