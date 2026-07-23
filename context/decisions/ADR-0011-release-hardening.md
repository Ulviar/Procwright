# ADR-0011: Release hardening перед первым публичным релизом

## Статус

Принято.

## Контекст

Текущий baseline включает `run`, `interactive`, `lineSession`, `expect`, PTY, `listen`, diagnostics, Kotlin ergonomics,
pooling, protocol integrations и bounded stress suite. Для публикации нужны релизные инварианты: ясная
лицензия, политика версий, compatibility границы, кроссплатформенная CI-матрица и проверяемые Maven publications.

## Решение

Фиксируем release hardening как отдельный слой над runtime:

- лицензия проекта — Apache License 2.0;
- root и optional modules наследуют единые `group` и `version`, чтобы релизная версия была версией всего проекта;
- Java release variants — 17/21/25 через единый source tree и Gradle property `procwright.javaRelease`;
- публичные пакеты стабилизируются на уровне core package family из ADR-0014, `io.github.ulviar.procwright.kotlin` и
  `io.github.ulviar.procwright.integration`;
- `quickCheck`, `scenarioCheck` и `regressionCheck` разделяют unit, integration и bounded stress tests;
- `:procwright-kotlin:javadocJar` запускает Dokka-проверку с
  `reportUndocumented=true` и `failOnWarning=true`; Java modules собирают Javadoc и Javadoc artifacts;
- CI проверяет Java 17 artifact на Linux/macOS/Windows с JDK 17 и на Linux с JDK 21/25; source targets 21/25
  отдельно проходят scenario checks на Linux;
- POSIX shell/PTTY fixtures skip-аются на Windows, если сценарий реально требует `sh` или системный PTY provider;
- versioning, compatibility, dependency и publication-readiness policy живут в `context/release/`.

## Последствия

Релизные требования становятся проверяемой частью проекта. Документация описывает обязательства текущего baseline.
Remote publishing/signing выбирается непосредственно перед первым release по актуальным требованиям registry;
performance experiments не входят в publication-readiness gate.
