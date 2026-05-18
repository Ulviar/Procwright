# Независимое scenario testing

Эта папка фиксирует независимую human-factor проверку iCLI и похожих библиотек. Она дополняет runnable
`:icli-comparison` harness, но не заменяет его: здесь оценивается, как реальные сценарии выглядят глазами пользователя
библиотеки.

## Кандидаты

- iCLI rewrite.
- JDK `ProcessBuilder`.
- Apache Commons Exec.
- ZeroTurnaround zt-exec.
- NuProcess.
- Pty4J.
- ExpectIt.

## Правило независимости

Исполнители scenario reports не должны опираться на прежние выводы iCLI comparison. Нельзя использовать как источник
оценок:

- `context/comparison/results.md`;
- `context/comparison/qualitative-assessment.md`;
- `context/decisions/ADR-0012-scenario-first-after-library-comparison.md`;
- внутреннюю философию проекта за пределами публичного API и обычных build files.

Разрешено смотреть публичный API, исходный код текущих candidate adapters, build dependencies и внешнюю документацию
библиотек. Если библиотека не предназначена для сценария, это фиксируется как unsupported или workaround, а не
наказывается как runtime failure.

## Выходные файлы

Каждый сценарий получает отдельный отчет в `reports/ISxx-*.md`. Итоговый сводный отчет собирается в
`summary.md`.
