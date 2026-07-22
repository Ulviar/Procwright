# ADR-0019: Проверка KDoc и публикация Kotlin API

## Статус

Принято.

## Контекст

`:procwright-kotlin` — optional ergonomics module поверх Java core. Его public declarations должны иметь проверяемый
KDoc, а пользовательская документация должна объяснять artifact, package и основные Kotlin-сценарии. Отдельный
generated API site имеет собственную стоимость поддержки и должен появляться только при доказанной пользе для
пользователя.

## Решение

- Dokka 2.2.0 входит в Gradle build как KDoc gate.
- `:procwright-kotlin:javadocJar` запускает `dokkaGeneratePublicationHtml` с `reportUndocumented=true` и
  `failOnWarning=true`.
- Output этой проверки в `procwright-kotlin/build/kdoc-validation` упаковывается в стандартный
  `procwright-kotlin-<version>-javadoc.jar`; пустой Java Javadoc classifier для Kotlin-only module не публикуется.
- Public Kotlin reference и scenario examples поддерживаются в основной документации. Generated KDoc доступен через
  Javadoc classifier, но не дублируется внутри MkDocs site.
- Отдельный Dokka site и его публикация отложены до появления подтвержденной пользовательской потребности.

## Последствия

- Build использует официальный Kotlin documentation tool и считает отсутствие KDoc ошибкой.
- Dokka остается build-time dependency, закрепленной dependency verification metadata, и не меняет runtime dependency
  surface.
- Стандартный Javadoc classifier содержит реальную generated Kotlin API reference. Отдельный Dokka site не дублирует
  Kotlin reference без решения о навигации, versioning и пользовательской пользе.
