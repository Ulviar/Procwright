# Каталог сценариев сравнения библиотек

## Назначение

Этот каталог фиксирует реальные workflow, под которые проектировался iCLI API, и переводит их в детерминированные
проверки для сравнения библиотек запуска процессов.

Сравнение не должно доказывать, что текущий iCLI лучше остальных. Его задача — показать, какие сценарии каждая
библиотека закрывает надежно, где API требует ручной сборки harness, и какие свойства нужно сохранить в iCLI.

## Библиотеки-кандидаты

- JDK `ProcessBuilder` — baseline без внешних dependencies.
- Apache Commons Exec `1.6.0` — зрелая библиотека с `DefaultExecutor`, `PumpStreamHandler` и `ExecuteWatchdog`.
- ZeroTurnaround zt-exec `1.12` — fluent wrapper над process execution.
- NuProcess `3.0.0` — non-blocking process I/O через native platform APIs.
- Pty4J `0.13.12` — PTY process library с Linux/macOS/Windows support.
- ExpectIt `0.9.0` — Expect-style automation поверх потоков процесса.
- iCLI — текущий scenario-first API.

## Сценарии

| ID | Реальный кейс | Детерминированная проверка |
| --- | --- | --- |
| S01 | `git status --short`, `python --version`, codegen command | One-shot command returns exit `0`, stdout captured, stderr drained. |
| S02 | CLI validator/linter returns diagnostics and non-zero exit | Non-zero command preserves stdout/stderr and exit code without throwing away diagnostics. |
| S03 | Tool receives stdin: formatter, compiler, JSON processor | Process receives stdin bytes and echoes a deterministic response. |
| S04 | Environment diagnostics: `env`, cloud CLIs, credentials-free probes | Environment override reaches the child process without shell interpolation. |
| S05 | Large command output: build logs, `git diff`, generated files | Large stdout/stderr are drained without deadlock and retained through bounded capture. |
| S06 | Hung command: network CLI, package manager, stuck build tool | Timeout terminates the child and reports a timeout-specific outcome. |
| S07 | Follow logs: `kubectl logs -f`, local dev server logs | Listener receives stdout/stderr chunks while the process is still running. |
| S08 | REPL / language server line protocol | A long-lived process accepts multiple line requests without interleaving responses. |
| S09 | Prompt automation: login prompt, installer prompt, debugger prompt | Expect-style helper waits for prompt text, sends a response, then observes completion. |
| S10 | Terminal-required tools: shell, TUI probes, programs checking `isatty` | PTY-backed run reports TTY mode, while pipe-only candidates are marked unsupported. |
| S11 | Warm worker pool: repeated formatter/compiler/REPL requests | Reusing a worker avoids per-request process startup and preserves bounded lifecycle. |
| S12 | Agent/tool adapter safety | CLI output is treated as untrusted data; adapter must return structured observations. |

## Что автоматизировано в текущем модуле

Первый runnable harness покрывает S01-S10. S11 покрывается runnable iCLI scenario и capability status для остальных
кандидатов. S12 выполняется через optional `:icli-integrations` для iCLI; для остальных библиотек фиксируется, что
structured tool observation требует отдельного adapter layer поверх process API.
