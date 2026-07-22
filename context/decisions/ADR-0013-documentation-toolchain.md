# ADR-0013: Публичная документация и toolchain

## Статус

Принято.

## Контекст

Новый пользователь должен выбрать сценарий, установить библиотеку и собрать корректный пример без знания внутренней
архитектуры. Javadocs недостаточно для навигации по задачам, а `context/` предназначен только для актуальных решений
LLM-агентов.

## Решение

- Публичные англоязычные материалы живут в `docs/`; русский `context/` не публикуется.
- Diataxis используется как способ разделить tutorial/how-to/reference/explanation, но не как причина создавать
  страницы без пользовательской задачи.
- MkDocs Material собирает статический сайт; `publicDocsCheck` запускает strict build из hash-pinned
  `docs/requirements.lock`.
- CI/docs jobs явно выбирают Python 3.13.14 и uv 0.10.12; используемые GitHub Actions зафиксированы exact commit
  SHA.
- Java reference генерируется Javadoc для core и integrations.
- Kotlin имеет task-oriented reference; `javadocJar` использует официальный Dokka 2.2.0 как KDoc gate с
  `reportUndocumented=true` и `failOnWarning=true`. Отдельный Dokka site не публикуется без доказанной
  пользовательской пользы.
- Публичные Java snippets должны быть скопированы из compile-tested example sources; ссылки проверяются strict docs
  build и repository tests.
- `preparePublicDocs` включает generated Javadocs во вход MkDocs, поэтому относительные API links проверяются strict
  build; после сборки оригинальные Javadoc assets копируются без преобразования.

## Инварианты

- Документация описывает только доказанное кодом и тестами поведение.
- У каждой страницы есть пользовательская задача; бесполезная полнота удаляется.
- Publication-readiness gate включает `publicDocsCheck` и строгий Javadoc.
- Изменение dependency coordinates, public API или observable contract обновляет соответствующие public pages и
  compile-tested examples в том же шаге.

## Последствия

Docs toolchain остается вне runtime artifacts, но требует Python dependencies для documentation gate. Lock обновляется только
при осмысленном изменении top-level docs requirements.
