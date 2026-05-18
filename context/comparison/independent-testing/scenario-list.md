# Список сценариев

## IS01: One-shot automation with diagnostics

Реальный кейс: `git status --short`, `terraform validate`, `python --version`, генератор кода или линтер.

Сценарий должен покрыть direct argv, working directory, environment override, stdin, bounded stdout/stderr, non-zero exit
и timeout outcome.

## IS02: Hung process and process-tree cleanup

Реальный кейс: зависший пакетный менеджер, сетевой CLI, build tool, который породил дочерний процесс.

Сценарий должен показать timeout, bounded cleanup, сохранение диагностик и поведение при дочерних процессах.

## IS03: Streaming log follower

Реальный кейс: `kubectl logs -f`, `tail -f`, dev server logs.

Сценарий должен получать stdout/stderr chunks пока процесс еще жив, уметь завершаться по timeout/cancel и фиксировать
listener failure semantics.

## IS04: Line-oriented REPL / worker protocol

Реальный кейс: compiler daemon, formatter daemon, language REPL, JSONL worker.

Сценарий должен отправить несколько line requests в один долгоживущий процесс, не смешивать ответы и иметь bounded
failure behavior при malformed или слишком длинной строке.

## IS05: Prompt automation

Реальный кейс: installer prompt, debugger prompt, login-like local prompt, `ssh-keygen` confirmation flow.

Сценарий должен дождаться prompt text/regex, отправить ответ, дождаться результата, сохранить transcript и не
раскрывать чувствительные send/expect values без явного решения пользователя.

## IS06: Terminal-required command

Реальный кейс: shell/TUI probe, command that checks `isatty`, terminal size-sensitive CLI.

Сценарий должен запросить PTY, проверить TTY mode и terminal size propagation или явно объяснить unsupported state.

## IS07: Warm worker pool

Реальный кейс: повторные formatter/compiler requests, дорогой CLI startup, несколько независимых воркеров.

Сценарий должен показать worker reuse, bounded acquire, health/reset или cleanup behavior.

## IS08: Command-backed structured tool

Реальный кейс: агент или сервер вызывает внешний CLI как tool и должен вернуть структурированное наблюдение, а не
исполнить CLI output как инструкцию.

Сценарий должен показать success result, structured error, bounded output, redaction-safe diagnostics и JSON/JSONL или
другую structured boundary.
