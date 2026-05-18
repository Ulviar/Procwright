# ADR-0019: Kotlin generated docs перед первым release candidate

## Статус

Accepted for the first release-candidate baseline.

## Контекст

`:icli-kotlin` является optional ergonomics module. Он добавляет Kotlin receiver extensions, suspending wrappers и Flow
adapter поверх Java core. Core не зависит от Kotlin runtime. Сейчас Kotlin public API проверяется source-level KDoc
gate, но public site не публикует generated Kotlin API docs.

Нужно решить, добавлять ли Dokka перед первым RC.

## Решение

Первый release candidate сохраняет KDoc-only publication model для Kotlin module:

- Kotlin public declarations должны иметь KDoc;
- `:icli-kotlin:kotlinApiDocsCheck` остается обязательной проверкой;
- public docs и release pages явно говорят, что generated Kotlin API docs отложены;
- Dokka не добавляется в текущий build до Kotlin API stabilization.

## Почему Dokka откладывается

- Kotlin module пока является ergonomics layer, а не основной runtime contract.
- Документационный site уже публикует Java Javadocs для core и integrations.
- Добавление Dokka меняет build dependency surface и требует отдельного review.
- До API freeze Kotlin wrappers могут изменяться чаще, чем core Java contracts.

## Последствия

Плюсы:

- Build остается проще перед первым RC.
- Kotlin API все равно защищен KDoc coverage check и tests.
- Dokka можно добавить отдельным focused change после Kotlin API stabilization.

Минусы:

- Public site пока не содержит generated Kotlin API docs.
- Kotlin пользователю нужно читать KDoc в sources artifact и scenario docs.
