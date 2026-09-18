# ADR-0011: Release hardening перед первым публичным релизом

## Статус

Принято.

## Контекст

Планируемый scope первого релиза включает `run`, `interactive`, `lineSession`, `expect`, PTY, `listen`, diagnostics,
Kotlin ergonomics, pooling, protocol integrations и bounded stress suite. Для публикации нужны релизные инварианты: ясная
лицензия, политика версий, compatibility границы, кроссплатформенная CI-матрица и проверяемые Maven publications.

## Решение

Фиксируем release hardening как отдельный слой над runtime:

- лицензия проекта — Apache License 2.0;
- root и optional modules наследуют единые `group` и `version`, чтобы релизная версия была версией всего проекта;
- Java 25 — единый runtime, compilation target и toolchain по [ADR-0026](ADR-0026-java25-baseline.md);
- публичные пакеты стабилизируются на уровне core package family из ADR-0014, `io.github.ulviar.procwright.kotlin` и
  `io.github.ulviar.procwright.integration`;
- `quickCheck`, `scenarioCheck` и `regressionCheck` разделяют unit, integration и bounded stress tests;
- `:procwright-kotlin:javadocJar` запускает Dokka-проверку с
  `reportUndocumented=true` и `failOnWarning=true`; Java modules собирают Javadoc и Javadoc artifacts;
- CI проверяет Java 25 на Linux/macOS/Windows; Linux также выполняет regression/stress и publication consumers;
- POSIX shell/PTTY fixtures skip-аются на Windows, если сценарий реально требует `sh` или системный PTY provider;
- versioning, compatibility, dependency и publication-readiness policy живут в `context/release/`.

## Последствия

Релизные требования становятся проверяемой частью проекта. Документация описывает scope планируемого первого релиза.
Remote publishing/signing выбирается непосредственно перед первым release по актуальным требованиям registry;
performance experiments не входят в publication-readiness gate.
