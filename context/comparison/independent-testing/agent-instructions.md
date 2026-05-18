# Инструкции независимому исполнителю

Ты оцениваешь один сценарий без учета внутренней философии iCLI и без чтения прежних выводов comparison.

## Что сделать

1. Реализуй сценарий на всех кандидатах:
   - iCLI rewrite;
   - JDK `ProcessBuilder`;
   - Apache Commons Exec;
   - ZeroTurnaround zt-exec;
   - NuProcess;
   - Pty4J;
   - ExpectIt.
2. Если кандидат не предназначен для сценария, зафиксируй `unsupported` или `workaround`, но все равно оцени
   пригодность.
3. Для каждой реализации укажи core code sketch и LOC по правилам `rubric.md`.
4. Поставь оценки `api`, `docs`, `library`, `scenario` по 100-балльной шкале.
5. Кратко объясни оценки через наблюдения по API, documentation discoverability, failure semantics и зрелости.
6. В конце дай вывод по сценарию: где лучший fit, где самый короткий код, где самый надежный contract.

## Ограничения

- Не читать `context/comparison/results.md`, `context/comparison/qualitative-assessment.md`,
  `context/decisions/ADR-0012-scenario-first-after-library-comparison.md`.
- Не изменять код библиотеки и build files.
- Пиши отчет на русском.
- Кодовые идентификаторы, snippets и названия API оставляй на английском.
- Не оценивай iCLI мягче или жестче из-за того, что он текущий проект.

## Формат отчета

````markdown
# ISxx: Название

## Scenario

...

## Implementations

### iCLI rewrite

```java
...
````

LOC: ...
Status: implemented | workaround | unsupported
API: ...
Docs: ...
Library: ...
Scenario: ...
Notes: ...

...

## Summary

...
```
