# ADR-0011: Release hardening перед первым OSS-кандидатом

## Статус

Accepted.

## Контекст

После фаз `run`, `interactive`, `lineSession`, `expect`, PTY, `listen`, diagnostics, Kotlin ergonomics, pooling,
presets, CLI-backed integrations и bounded stress suite проект уже можно оценивать как библиотеку, а не только как
эксперимент. Для этого нужны релизные инварианты: ясная лицензия, политика версий, compatibility границы,
кроссплатформенная CI-матрица и release checklist.

## Решение

Фиксируем release hardening как отдельный слой над runtime:

- лицензия проекта — Apache License 2.0, перенесенная из старой ветки без изменения текста;
- текущая версия остается `0.0.0-SNAPSHOT`, публичный релиз еще не публикуется;
- root и optional modules наследуют единые `group` и `version`, чтобы релизная версия была версией всего проекта;
- Java release variants — 17/21/25 через единый source tree и Gradle property `icli.javaRelease`;
- публичные пакеты стабилизируются на уровне core package family из ADR-0014, `com.github.ulviar.icli.kotlin` и
  `com.github.ulviar.icli.integration`;
- Gradle `check` включает unit, integration, Kotlin, integrations и bounded stress tests;
- Kotlin public API покрывается KDoc source check в `:icli-kotlin:kotlinApiDocsCheck`; Java modules собирают Javadoc и
  Javadoc artifacts;
- CI запускает `check` и `javadoc` на Linux, macOS и Windows;
- POSIX shell/PTTY fixtures skip-аются на Windows, если сценарий реально требует `sh` или системный PTY provider;
- release policy, compatibility policy, dependency review, release checklist и migration notes живут в `context/release/`.

## Последствия

Релизные требования становятся проверяемой частью проекта. Документация больше не ограничивается roadmap: она описывает,
какие обязательства библиотека готова принять перед первым публичным артефактом. Реальный release publishing, signing,
Maven Central metadata и тяжелые benchmarks остаются отдельной будущей фазой.
