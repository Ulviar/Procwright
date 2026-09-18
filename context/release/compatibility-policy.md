# Политика совместимости

## Базовая runtime-платформа

- Java 25 — единственные minimum runtime, compilation target и toolchain разработки/CI всех модулей.
- Публикуемые Java/Kotlin classes имеют major version 69 без preview flag; Gradle library variants требуют JVM 25.
  Эти свойства всех трёх JAR проверяет `publicationStructureCheck`.
- Kotlin module компилируется Kotlin 2.4.20 с JVM target 25 и остаётся optional. Consumer compiler должен читать
  Kotlin 2.4 metadata; совместимость с более старыми compiler versions не заявлена.
- Lifecycle tasks используют прямые virtual-thread API. Bounded isolation workers, PTY admission и scheduler
  сохраняют platform threads и собственные лимиты; virtual threads не заменяют admission/cleanup policies.
- Optional integrations экспортирует Jackson 3 `tools.jackson.databind.JsonNode` и JPMS module `tools.jackson.databind`.
- Решение и границы модернизации: [ADR-0026](../decisions/ADR-0026-java25-baseline.md).

## Поддержка платформ

Обязательная CI-матрица для публичного релиза:

- Linux Ubuntu 24.04;
- macOS latest;
- Windows 2025 hosted runner.

Все три ОС проверяются на Temurin JDK 25. Linux запускает regression/stress и publication consumers, macOS и Windows —
scenario gate. Linux и macOS требуют system PTY; его отсутствие на этих контролируемых runners является ошибкой.

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
