# Итог независимого scenario testing

## Метод

Проверка была разбита на восемь реальных сценариев использования CLI-библиотек. Каждый сценарий оценивал отдельный
worker без чтения прежних выводов `:icli-comparison`. Исполнители получили общий список сценариев, rubric и запрет на
использование `context/comparison/results.md`, `context/comparison/qualitative-assessment.md` и ADR-0012 как источников
оценок.

Оценки даны по 100-балльной шкале:

- `API` — насколько прямо сценарий выражается пользовательским API.
- `Docs` — насколько легко найти и собрать решение по документации.
- `Library` — насколько библиотека зрелая и пригодная именно для сценария.
- `Scenario` — `round(0.4 * API + 0.2 * Docs + 0.4 * Library)`.
- `LOC` — nonblank noncomment lines для core implementation sketch. LOC не входит в score.

## Сценарии

| ID | Сценарий | Реальный кейс |
| --- | --- | --- |
| IS01 | One-shot automation with diagnostics | `git status`, linter, codegen, validator with stdin/stdout/stderr/timeout. |
| IS02 | Hung process and process-tree cleanup | Stuck package manager/build tool with child processes. |
| IS03 | Streaming log follower | `kubectl logs -f`, `tail -f`, dev server logs. |
| IS04 | Line-oriented REPL / worker protocol | Formatter daemon, compiler daemon, REPL, JSONL worker. |
| IS05 | Prompt automation | Installer prompt, debugger prompt, local login-like prompt. |
| IS06 | Terminal-required command | TUI/shell probe, `isatty`, terminal size-sensitive command. |
| IS07 | Warm worker pool | Repeated formatter/compiler requests with expensive startup. |
| IS08 | Command-backed structured tool | Agent/tool adapter around an external CLI with structured observations. |

## Scenario Scores

| Candidate | IS01 | IS02 | IS03 | IS04 | IS05 | IS06 | IS07 | IS08 | Avg |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| iCLI rewrite | 90 | 91 | 88 | 90 | 87 | 83 | 89 | 88 | 88.3 |
| JDK ProcessBuilder | 70 | 66 | 66 | 64 | 70 | 30 | 64 | 67 | 62.1 |
| ZeroTurnaround zt-exec | 80 | 73 | 74 | 56 | 64 | 27 | 56 | 73 | 62.9 |
| Apache Commons Exec | 74 | 67 | 73 | 56 | 60 | 29 | 54 | 71 | 60.5 |
| NuProcess | 61 | 59 | 75 | 73 | 57 | 30 | 55 | 62 | 59.0 |
| Pty4J | 45 | 44 | 49 | 47 | 55 | 86 | 46 | 47 | 52.4 |
| ExpectIt | 41 | 33 | 34 | 59 | 82 | 46 | 48 | 48 | 48.9 |

## Average Dimensions

| Candidate | API avg | Docs avg | Library avg | Scenario avg | LOC avg | Implemented / workaround / unsupported |
| --- | ---: | ---: | ---: | ---: | ---: | --- |
| iCLI rewrite | 92.1 | 85.5 | 85.5 | 88.3 | 18.4 | 8 / 0 / 0 |
| JDK ProcessBuilder | 49.1 | 73.9 | 69.5 | 62.1 | 56.9 | 3 / 4 / 1 |
| ZeroTurnaround zt-exec | 58.0 | 68.9 | 64.8 | 62.9 | 55.4 | 1 / 6 / 1 |
| Apache Commons Exec | 50.4 | 69.5 | 66.5 | 60.5 | 61.4 | 1 / 6 / 1 |
| NuProcess | 49.4 | 61.3 | 67.5 | 59.0 | 70.3 | 3 / 4 / 1 |
| Pty4J | 43.9 | 54.5 | 60.3 | 52.4 | 56.0 | 1 / 7 / 0 |
| ExpectIt | 39.0 | 59.8 | 52.8 | 48.9 | 38.3 | 1 / 5 / 2 |

`IS04` для Pty4J содержит caveat: полный line-session workaround оценен как 91 LOC, а только PTY-specific wiring — 14
LOC. В сводке используется полное число, потому что сценарий требует line protocol, а не только запуска PTY.

## Per-Scenario Winners

| Scenario | Highest score | Best specialized fit | Shortest implemented/workaround sketch |
| --- | --- | --- | --- |
| IS01 | iCLI rewrite, 90 | zt-exec остается сильным fluent one-shot вариантом, 80 | iCLI rewrite, 22 LOC |
| IS02 | iCLI rewrite, 91 | zt-exec проще остальных external wrappers, 73 | iCLI rewrite, 14 LOC |
| IS03 | iCLI rewrite, 88 | NuProcess естественно подходит streaming/non-blocking I/O, 75 | iCLI rewrite, 16 LOC |
| IS04 | iCLI rewrite, 90 | NuProcess хорошо подходит долгоживущему protocol worker, 73 | iCLI rewrite, 20 LOC |
| IS05 | iCLI rewrite, 87 | ExpectIt сильнее всех specialized prompt libraries, 82 | iCLI rewrite, 19 LOC; ExpectIt, 23 LOC |
| IS06 | Pty4J, 86 | Pty4J — лучший прямой PTY fit | iCLI rewrite, 13 LOC; Pty4J, 17 LOC |
| IS07 | iCLI rewrite, 89 | внешние кандидаты требуют ручного pool manager | iCLI rewrite, 17 LOC |
| IS08 | iCLI rewrite, 88 | zt-exec и Commons Exec неплохи как command runners, но без structured tool layer | zt-exec, 23 LOC; iCLI rewrite, 26 LOC |

## Выводы

iCLI rewrite получил лучший средний результат не за счет низкоуровневой полноты process API, а за счет scenario-first
поверхности: каждый проверенный workflow имеет отдельный пользовательский entry point или тонкий typed helper. Это
снижает объем glue-кода и переносит инварианты в библиотеку: timeout outcome, bounded retention, line-session ownership,
expect transcript policy, worker pooling и structured tool boundary.

Внешние библиотеки выглядят сильнее там, где их scope совпадает со сценарием:

- Pty4J — лучший выбор для raw PTY/terminal transport.
- ExpectIt — зрелый специализированный вариант для prompt automation.
- NuProcess — сильный кандидат для streaming и долгоживущих protocol workers, если пользователь готов писать handler
  state machine.
- zt-exec — самый удобный внешний wrapper для простого one-shot process execution.
- JDK `ProcessBuilder` остается надежной baseline, но почти во всех сложных сценариях требует собственного harness.
- Commons Exec закрывает классические one-shot/timeout задачи, но для interactive, streaming и pooling быстро
  превращается в ручную сборку.

Главный риск для iCLI по итогам независимых отчетов — документация. API scores у iCLI стабильно высокие, но Docs avg
ниже API avg: нужно развивать cookbook и scenario-by-scenario reference, иначе преимущества API будут хуже
обнаруживаться новым пользователем.

## Source Reports

- [IS01-one-shot-automation.md](reports/IS01-one-shot-automation.md)
- [IS02-timeout-tree-cleanup.md](reports/IS02-timeout-tree-cleanup.md)
- [IS03-streaming-log-follower.md](reports/IS03-streaming-log-follower.md)
- [IS04-line-repl-worker.md](reports/IS04-line-repl-worker.md)
- [IS05-prompt-automation.md](reports/IS05-prompt-automation.md)
- [IS06-terminal-required-command.md](reports/IS06-terminal-required-command.md)
- [IS07-warm-worker-pool.md](reports/IS07-warm-worker-pool.md)
- [IS08-command-backed-structured-tool.md](reports/IS08-command-backed-structured-tool.md)
