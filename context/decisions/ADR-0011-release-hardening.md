# ADR-0011: Release hardening перед первым публичным релизом

## Статус

Accepted.

## Контекст

Текущий baseline включает `run`, `interactive`, `lineSession`, `expect`, PTY, `listen`, diagnostics, Kotlin ergonomics,
pooling, presets, CLI-backed integrations и bounded stress suite. Для публикации нужны релизные инварианты: ясная
лицензия, политика версий, compatibility границы, кроссплатформенная CI-матрица и release checklist.

## Решение

Фиксируем release hardening как отдельный слой над runtime:

- лицензия проекта — Apache License 2.0;
- root и optional modules наследуют единые `group` и `version`, чтобы релизная версия была версией всего проекта;
- Java release variants — 17/21/25 через единый source tree и Gradle property `icli.javaRelease`;
- публичные пакеты стабилизируются на уровне core package family из ADR-0014, `io.github.ulviar.icli.kotlin` и
  `io.github.ulviar.icli.integration`;
- Gradle `check` включает unit, integration, Kotlin, integrations и bounded stress tests;
- Kotlin public API покрывается KDoc source check в `:icli-kotlin:kotlinApiDocsCheck`; Java modules собирают Javadoc и
  Javadoc artifacts;
- CI запускает `check` и `javadoc` на Linux, macOS и Windows;
- POSIX shell/PTTY fixtures skip-аются на Windows, если сценарий реально требует `sh` или системный PTY provider;
- release policy, compatibility policy, dependency review и release checklist живут в `context/release/`.

## Последствия

Релизные требования становятся проверяемой частью проекта. Документация описывает обязательства текущего baseline.
Release publishing/signing закрыт отдельным ADR, а тяжелые benchmarks остаются отдельным research layer.
