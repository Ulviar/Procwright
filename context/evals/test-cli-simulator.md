# Тестовое CLI-приложение для моделирования проблем процессов

## Назначение

Модуль `:icli-test-cli` содержит отдельное Java CLI-приложение, которое моделирует реальные проблемы CLI-процессов для
integration, stress, comparison и regression проверок iCLI. Оно не зависит от core, Kotlin, integrations или comparison
модулей и не является публичным API библиотеки.

Главная цель — дать тестам один воспроизводимый child process с широким набором problem modes. Это заменяет рост
разрозненных fixture-программ и позволяет расширять проверки через сценарии, не меняя process runtime iCLI.

Запуск:

```bash
./gradlew :icli-test-cli:run --args='catalog'
./gradlew :icli-test-cli:run --args='stream --count=10 --delay-millis=25'
./gradlew :icli-test-cli:run --args='line-repl --prompt="ready> "'
```

## Инварианты симулятора

- Сценарий выбирается явно: первым аргументом или через `--scenario=<name>`.
- Поведение детерминировано по умолчанию; nondeterministic-style режимы используют `--seed`.
- Размеры output ограничиваются параметрами сценария, а не бесконечными генераторами.
- Протокольные сценарии читают stdin и пишут stdout/stderr как реальный дочерний процесс.
- Non-zero exit, malformed output, partial output и hangs являются штатными моделируемыми результатами, а не ошибками
  самого симулятора.
- Симулятор не закрепляет философию public API iCLI; он проверяет runtime-реальность, с которой сценарный API должен
  справляться.

## Каталог проблем

### Lifecycle

- `exit` — stdout/stderr плюс настраиваемый exit code, startup delay и exit delay.
- `sleep` — медленное завершение с маркерами start/finish.
- `never-exit` — процесс, который не завершится без внешнего shutdown.
- `shutdown-hook` — JVM shutdown hook с задержкой, полезен для timeout/escalation cleanup.
- `spawn-child` — parent process порождает child process, чтобы проверять process-tree cleanup.

### Output

- `stream` — interleaved stdout/stderr chunks, задержки, flush boundaries, optional newline.
- `burst` — большие независимые burst-потоки stdout/stderr для deadlock и bounded capture проверок.
- `partial` — незавершенные stdout/stderr без newline.
- `binary` — raw bytes, включая NUL, `0xff`, диапазоны и hex-patterns.
- `ansi-prompt` — ANSI/control-sequence prompt без newline.

### Stdin

- `stdin-echo` — чтение stdin как text, hex или byte count, включая slow byte-by-byte read.
- `ignore-stdin` — процесс живет, но не дренирует stdin.

### Protocol

- `line-repl` — line-oriented REPL с prompt, stderr command, sleep command, multi-line response и controlled exit.
- `jsonl` — JSON Lines протокол с опционально malformed responses.
- `content-length` — Content-Length framed protocol с проверкой incomplete frames и malformed response mode.

### Launch и terminal

- `argv-env-cwd` — проверка argv, выбранных env-переменных и cwd.
- `terminal-check` — моделирование CLI, которому нужен terminal/console.

### Nondeterminism

- `flaky` — deterministic-by-seed failure/delay/hang модель для retry и diagnostics проверок.

## Расширение

Новый scenario добавляется только вместе с тестом в `:icli-test-cli:test`. Если scenario нужен для release-relevant
поведения iCLI, соответствующая проверка должна появиться в `integrationTest`, `stressTest`, `comparisonCheck` или
другом release gate, а не оставаться только в каталоге симулятора.
