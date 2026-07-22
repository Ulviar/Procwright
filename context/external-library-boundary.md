# Граница внешних библиотек

## Назначение

Сравнение библиотек нужно использовать как исследование и источник идей, а не как повод тащить чужую модель в public
API Procwright. Core остается scenario-first JVM library с минимальными зависимостями и собственными инвариантами вокруг
process lifecycle.

## Разрешенная область

Внешние process libraries из исследования разрешены только в `:procwright-comparison`:

- Apache Commons Exec;
- ZeroTurnaround zt-exec;
- NuProcess;
- Pty4J;
- ExpectIt.

`context/comparison/*` может описывать эти библиотеки текстом. Код и runtime classpath публичных артефактов не должны
зависеть от них.

## Запрещено

- Добавлять эти зависимости в root module, `:procwright-kotlin` или `:procwright-integrations`.
- Раскрывать типы этих библиотек в public API, exceptions, listeners или scenario Draft.
- Использовать внешний process runtime как обход `scenario Draft -> internal settings/plan -> runtime`.
- Переносить provider-specific flags в scenario Draft.
- Делать optional backend без отдельного ADR, dependency review и тестов границы.

## Что можно переносить

Можно переносить идеи:

- краткость fluent one-shot API из zt-exec;
- callbacks/backpressure lessons из NuProcess;
- expect-style matching patterns из ExpectIt;
- PTY capability isolation из Pty4J.

Переносится только доменная идея, реализованная через собственные scenario contracts и invariant owners Procwright.

## Проверяемый gate

`ExternalLibraryBoundaryTest` проверяет:

- comparison dependencies объявлены только в `procwright-comparison/build.gradle.kts`;
- core, Kotlin и integrations sources не импортируют и не используют packages comparison libraries;
- публичные build files не зависят от `:procwright-comparison`.

`externalLibraryBoundaryCheck` дополнительно проверяет resolved runtime classpath публичных модулей (`:`,
`:procwright-kotlin`, `:procwright-integrations`) и запрещает comparison dependencies на classpath.

Если будущий optional backend действительно понадобится, сначала нужен ADR, затем новый module boundary и обновление
этого gate.
