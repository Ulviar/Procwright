# План написания публичной документации

## Статус

Completed for the current release-candidate documentation baseline. Дальнейшие изменения должны идти как отдельные
docs tasks, связанные с новым API, behavior или release decision.

## Цель

Сделать документацию iCLI такой же scenario-first, как public API: пользователь должен быстро выбрать нужный workflow,
увидеть минимальный рабочий пример, понять гарантии сценария и перейти к reference без чтения внутреннего `context/`.

Публичная документация пишется на английском. Этот план и внутренний контекст пишутся на русском.

## Принципы

- Не обещать поведение, которое не подтверждено tests, compile-tested examples или release context.
- Начинать с пользовательского сценария, а не с перечня классов.
- Каждая публичная страница должна отвечать хотя бы на один вопрос пользователя: что выбрать, как применить, какие
  гарантии есть, где граница сценария.
- Javadocs/KDocs являются API reference, но не заменяют scenario docs.
- `context/` не публикуется как пользовательская документация без редакторской переработки.
- Новые docs должны усиливать инварианты iCLI: bounded output, explicit environment policy, timeout/shutdown,
  single output ownership, diagnostics redaction, optional capability boundary.

## Фазы

### Фаза 1: Documentation foundation

Задачи:

- Зафиксировать toolchain и границу `docs/`/`context/` в ADR.
- Поддерживать `mkdocs.yml`, `docs/requirements.txt` и `publicDocsCheck`.
- Сформировать карту документации: overview, getting started, scenarios, reference, explanation, API.
- Убедиться, что публичная scenario map связана с compile-tested examples.

Готовность:

- `./gradlew publicDocsCheck` проходит.
- `./gradlew test` содержит быстрый docs coverage check.
- README и release checklist знают про public docs gate.

### Фаза 2: Scenario documentation

Задачи:

- Для каждого core workflow описать: когда использовать, когда не использовать, пример, гарантии библиотеки,
  ответственность пользователя, failure semantics и границу сценария.
- Отдельно раскрыть `Expect`, terminal capability, diagnostics и presets, чтобы они не выглядели как случайные flags.
- Сохранить связь с compile-tested example names.

Готовность:

- Все core сценарии имеют публичные страницы.
- Страницы не вводят новых API names, которых нет в коде.
- `publicDocsCheck` проходит в strict mode.

### Фаза 3: How-to guides

Задачи:

- Написать task-oriented guides по реальным кейсам из comparison/independent testing:
  one-shot automation, hung process cleanup, streaming log follower, line-oriented worker, prompt automation,
  terminal-required command, warm worker pool, command-backed structured tool.
- Каждый guide должен начинаться с выбора сценария и объяснять, почему это не другой сценарий.
- Code snippets должны либо совпадать с compile-tested examples, либо получить отдельный compile-tested source.

Готовность:

- Есть how-to index и минимум один guide на каждый release-relevant workflow.
- Guides не дублируют reference полностью и не обещают неподтвержденное поведение.

### Фаза 4: Reference documentation

Задачи:

- Описать command model, policies, result/error model, diagnostics, security defaults, environment handling,
  PTY/platform boundary, integrations boundary.
- Для каждого reference раздела указать источник истины: Javadoc, tests, scenario contracts или release policy.
- Не превращать reference в tutorial.

Готовность:

- Пользователь может найти значение public policy/result/error без чтения production code.
- Security-sensitive defaults описаны явно и согласованы с regression tests.

### Фаза 5: Generated API docs

Задачи:

- Определить publication path для Javadocs внутри сайта или рядом с сайтом.
- Добавить Dokka, когда Kotlin API будет готов к публикации generated docs.
- При необходимости добавить link-check gate для generated docs и публичного сайта.

Готовность:

- Java API docs доступны из public docs.
- Kotlin generated docs добавлены только после решения по Dokka и Kotlin API stabilization.

### Фаза 6: Release docs

Задачи:

- Подготовить installation coordinates, compatibility, migration notes, known limitations и release notes template.
- Убедиться, что pre-release warnings удалены или обновлены перед первым release candidate.

Готовность:

- First release candidate можно оценивать по README, public docs, Javadocs, release checklist и migration notes без
  обращения к внутренним планам.

## Выполнение

- Фаза 1 выполнена: toolchain, ADR, MkDocs gate, README/release references и docs coverage tests добавлены.
- Фаза 2 выполнена: core scenario pages, Expect, terminal capability, presets и integrations boundary оформлены в
  public docs.
- Фаза 3 выполнена для текущего release-candidate baseline: добавлены how-to guides для one-shot automation, hung
  process cleanup, streaming log follower, line-oriented worker, prompt automation, terminal-required command, warm
  worker pool и command-backed structured tool.
- Фаза 4 выполнена в базовом объеме: добавлены reference pages для command model, policies, results/errors,
  diagnostics, security и platform/PTY boundary.
- Фаза 5 выполнена для Java API: `publicDocsCheck` строит MkDocs site и подкладывает generated Javadocs в public site
  output. Kotlin Dokka publication отложена до Kotlin API stabilization.
- Фаза 6 выполнена в базовом объеме: добавлены installation, compatibility, known limitations, migration notes и release
  notes template.

Следующие расширения должны идти не через увеличение README, а через how-to guides, reference pages или release docs с
привязкой к compile-tested examples.

## Проверки

Минимальный локальный набор для docs-шага:

```bash
./gradlew test
./gradlew publicDocsCheck
./gradlew spotlessCheck
git diff --check
```

Для release-кандидата используется полный gate из `context/release/release-checklist.md`.
