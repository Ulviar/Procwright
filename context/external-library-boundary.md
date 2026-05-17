# Граница внешних библиотек

## Назначение

Сравнение библиотек нужно использовать как исследование и источник идей, а не как повод тащить чужую модель в public
API iCLI. Core остается scenario-first JVM library с минимальными зависимостями и собственными инвариантами вокруг
process lifecycle.

## Разрешенная область

Внешние process libraries из исследования разрешены только в `:icli-comparison`:

- Apache Commons Exec;
- ZeroTurnaround zt-exec;
- NuProcess;
- Pty4J;
- ExpectIt.

`context/comparison/*` может описывать эти библиотеки текстом. Код и runtime classpath публичных артефактов не должны
зависеть от них.

## Запрещено

- Добавлять эти зависимости в root module, `:icli-kotlin` или `:icli-integrations`.
- Раскрывать типы этих библиотек в public API, exceptions, options, listeners или builders.
- Использовать внешний process runtime как обход `ScenarioProfile -> ExecutionPlanResolver -> runtime`.
- Переносить provider-specific flags в сценарные builders.
- Делать optional backend без отдельного ADR, dependency review и тестов границы.

## Что можно переносить

Можно переносить идеи:

- краткость fluent one-shot API из zt-exec;
- callbacks/backpressure lessons из NuProcess;
- expect-style matching patterns из ExpectIt;
- PTY capability isolation из Pty4J.

Переносится только доменная идея, реализованная через собственные scenario contracts и invariant owners iCLI.

## Проверяемый gate

`ExternalLibraryBoundaryTest` проверяет:

- comparison dependencies объявлены только в `icli-comparison/build.gradle.kts`;
- core, Kotlin и integrations sources не импортируют и не используют packages comparison libraries;
- публичные build files не зависят от `:icli-comparison`.

`externalLibraryBoundaryCheck` дополнительно проверяет resolved runtime classpath публичных модулей (`:`,
`:icli-kotlin`, `:icli-integrations`) и запрещает comparison dependencies на classpath.

Если будущий optional backend действительно понадобится, сначала нужен ADR, затем новый module boundary и обновление
этого gate.
