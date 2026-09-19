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
- CI/docs jobs явно выбирают Python 3.14.7 и uv 0.12.17; используемые GitHub Actions зафиксированы exact commit
  SHA.
- Java reference генерируется Javadoc для core и integrations с описаниями JPMS modules. Внутренние packages
  доступны генератору для разрешения типов, но исключены из публичных страниц.
- Kotlin имеет task-oriented reference и полный API reference, который генерирует Dokka 2.2.0 с
  `reportUndocumented=true` и `failOnWarning=true`. Один HTML output используется сайтом и `javadocJar`.
- Публичные Java snippets берутся из compile-tested example sources. Javadoc подключает их через `@snippet` regions;
  `publicJavaJavadocCheck` также компилирует эти источники отдельным consumer module.
- Integrations Javadoc и Kotlin API reference ссылаются на core API. Для разрешения ссылок оба генератора используют
  локальный `element-list` текущей сборки; доступ к опубликованному сайту для этого не требуется.
- `preparePublicDocs` включает generated Java/Kotlin API reference во вход MkDocs, поэтому относительные API links проверяются strict
  build; после сборки оригинальные API reference assets копируются без преобразования.

## Инварианты

- Документация описывает только доказанное кодом и тестами поведение.
- У каждой страницы есть пользовательская задача; бесполезная полнота удаляется.
- Publication-readiness gate включает `publicDocsCheck` и строгий Javadoc.
- Изменение dependency coordinates, public API или observable contract обновляет соответствующие public pages и
  compile-tested examples в том же шаге.

## Последствия

Docs toolchain остается вне runtime artifacts, но требует Python dependencies для documentation gate. Lock обновляется только
при осмысленном изменении top-level docs requirements.
