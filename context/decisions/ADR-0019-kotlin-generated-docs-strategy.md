# ADR-0019: Kotlin generated docs для baseline 0.1.0

## Статус

Принято.

## Контекст

`:icli-kotlin` является optional ergonomics module. Он добавляет Kotlin receiver extensions, suspending wrappers и Flow
adapter поверх Java core. Core не зависит от Kotlin runtime. Kotlin public API проверяется source-level KDoc gate.

Нужно решить, добавлять ли Dokka в текущий documentation toolchain.

## Решение

Baseline `0.1.0` не добавляет Dokka в build, но Kotlin API должен быть явно представлен в public docs:

- Kotlin public declarations должны иметь KDoc;
- `:icli-kotlin:kotlinApiDocsCheck` остается обязательной проверкой;
- public docs содержат страницу Kotlin API с artifact, package, extensions и основными usage examples;
- Dokka не добавляется в текущий build до Kotlin API stabilization.

## Почему Dokka откладывается

- Kotlin module пока является ergonomics layer, а не основной runtime contract.
- Документационный site уже публикует Java Javadocs для core и integrations.
- Добавление Dokka меняет build dependency surface и требует отдельного review.
- До API freeze Kotlin wrappers могут изменяться чаще, чем core Java contracts.

## Последствия

Плюсы:

- Build остается проще.
- Kotlin API все равно защищен KDoc coverage check и tests.
- Dokka можно добавить отдельным focused change после Kotlin API stabilization.

Минусы:

- Public site не содержит generated Dokka docs.
- Kotlin пользователю доступна public reference page, KDoc in sources artifact и scenario docs.
