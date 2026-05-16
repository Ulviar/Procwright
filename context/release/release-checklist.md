# Релизный checklist

Этот список применяется к первому OSS release candidate и каждому последующему релизу.

## Перед freeze

- README описывает только реализованное и протестированное поведение.
- `context/quality/scorecard.md` не содержит устаревшего статуса по уже завершенным фазам.
- Все ADR для публичных архитектурных решений добавлены в `context/decisions/`.
- Migration notes отражают переносимые идеи старой версии и то, что намеренно не переносится.
- Versioning и compatibility policies актуальны для текущего Java/Kotlin baseline.
- Dependency review не содержит неизвестных runtime dependencies.

## Локальные проверки

Обязательный набор:

```bash
./gradlew spotlessApply
./gradlew check --rerun-tasks
./gradlew javadoc --rerun-tasks
git diff --check
```

`check` включает unit, integration, module tests, Kotlin public API KDoc check и bounded stress suite.

## CI

GitHub Actions workflow должен пройти на:

- Linux;
- macOS;
- Windows.

POSIX-only и PTY-only tests должны skip-аться через assumptions, а не падать из-за недоступной платформенной
возможности.

## Перед публикацией

- Версия больше не `0.0.0-SNAPSHOT`.
- Release notes перечисляют shipped behavior, breaking changes и known limitations.
- Source и Javadoc artifacts собираются для Java modules.
- Kotlin public API задокументирован через KDoc в sources artifact и проверяется `:icli-kotlin:kotlinApiDocsCheck`.
- License file присутствует в корне репозитория.
- Если добавляется Maven Central publishing, signing и metadata оформляются отдельным ADR.
