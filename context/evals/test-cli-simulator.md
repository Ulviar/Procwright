# Тестовое CLI-приложение для моделирования проблем процессов

## Назначение

Модуль `:procwright-test-cli` содержит отдельное Java CLI-приложение, которое моделирует реальные проблемы CLI-процессов для
integration, stress и regression проверок Procwright. Оно не зависит от core, Kotlin или integrations
модулей и не является публичным API библиотеки.

Главная цель — дать тестам один воспроизводимый child process с широким набором problem modes. Это заменяет рост
разрозненных fixture-программ и позволяет расширять проверки через сценарии, не меняя process runtime Procwright.

Запуск:

```bash
./gradlew :procwright-test-cli:run --args='catalog'
./gradlew :procwright-test-cli:run --args='stream --count=10 --delay-millis=25'
./gradlew :procwright-test-cli:run --args='line-repl --prompt="ready> "'
```

## Инварианты симулятора

- Сценарий выбирается явно: первым аргументом или через `--scenario=<name>`.
- Поведение детерминировано по умолчанию; nondeterministic-style режимы используют `--seed`.
- Размеры output ограничиваются параметрами сценария, а не бесконечными генераторами.
- Протокольные сценарии читают stdin и пишут stdout/stderr как реальный дочерний процесс.
- Non-zero exit, malformed output, partial output и hangs являются штатными моделируемыми результатами, а не ошибками
  самого симулятора.
- Симулятор не закрепляет философию public API Procwright; он проверяет runtime-реальность, с которой сценарный API должен
  справляться.

## Каталог проблем

### Metadata

- `catalog` — печатает полный реестр сценариев, сгруппированный по семействам.

### Lifecycle

- `exit` — stdout/stderr плюс настраиваемый exit code, startup delay и exit delay.
- `sleep` — медленное завершение с маркерами start/finish.
- `never-exit` — процесс, который не завершится без внешнего shutdown.
- `shutdown-hook` — JVM shutdown hook с задержкой, полезен для timeout/escalation cleanup.
- `spawn-child` — parent process порождает child process, чтобы проверять process-tree cleanup.
- `spawn-tree` — parent запускает child, который запускает leaf process; проверяет cleanup настоящего дерева процессов.
- `repeat-spawn` — многократный запуск child-сценария для repeated loop и resource-leak harness.

### Output

- `stream` — interleaved stdout/stderr chunks, задержки, flush boundaries, optional newline.
- `concurrent-output` — синхронно запускает отдельные writer threads для stdout и stderr, чтобы воспроизводить
  конкурентную публикацию потоков.
- `long-run` — bounded heartbeat output для long-running/slow-consumer проверок; `--hold-millis` добавляет тихий
  живой хвост после heartbeats для idle-timeout проверок.
- `burst` — большие независимые burst-потоки stdout/stderr для deadlock и bounded capture проверок.
- `partial` — незавершенные stdout/stderr без newline.
- `binary` — raw bytes, включая NUL, `0xff`, диапазоны и hex-patterns; optional `--hold-millis` удерживает процесс
  после flush для session tests без передачи control characters через argv.
- `ansi-prompt` — ANSI/control-sequence prompt без newline.

### Stdin

- `stdin-echo` — чтение stdin как text, hex или byte count, включая slow byte-by-byte read.
- `ignore-stdin` — процесс живет, но не дренирует stdin.

### Protocol

- `line-repl` — line-oriented REPL с prompt, stderr command, sleep command, multi-line response и controlled exit.
- `controlled-line-repl` — line REPL с health/reset/pid/hold/noise/stderr-burst control requests для pool/session
  проверок; `malformed-utf8` пишет сырой невалидный UTF-8 байт, `split-utf8` разрезает multi-byte codepoint по границе
  flush для strict-charset и incremental-decoder проверок.
- `expect-near-match-repl` — один reusable process выдает paced bounded rounds из почти совпадающих chunks для
  проверки bounded matching без повторного process startup.
- `exit-after-read` — процесс читает один request и завершает stdout до ответа.
- `two-line-delay-repl` — сериализуемые two-line responses с задержкой между строками.
- `length-line-frame` — length-line framed protocol для произвольных multi-line/binary-style request bodies.
- `jsonl` — JSON Lines протокол с опционально malformed responses.
- `content-length` — Content-Length framed protocol с проверкой incomplete frames и malformed response mode.

### Launch и terminal

- `argv-env-cwd` — проверка argv, выбранных env-переменных и cwd.
- `terminal-check` — моделирование CLI, которому нужен terminal/console.

### Platform

- `platform-newlines` — явные `lf`, `crlf`, `cr` и system newline patterns для Windows/POSIX различий.
- `platform-probe` — OS/separator/newline metadata, видимые дочернему процессу.

### Load

- `mixed-load` — bounded CPU work, memory allocation и output ticks для конкуренции с системной нагрузкой.

### Nondeterminism

- `flaky` — deterministic-by-seed failure/delay/hang модель для retry и diagnostics проверок.

## Расширение

Новый scenario добавляется только вместе с тестом в `:procwright-test-cli:test`. Если scenario нужен для release-relevant
поведения Procwright, соответствующая проверка должна появиться в `integrationTest`, `stressTest` или другом release gate, а
не оставаться только в каталоге симулятора.

## Stress-проверки Procwright

`src/stressTest/java/io/github/ulviar/procwright/TestCliStressTest.java` использует симулятор как реальный child process и
проверяет:

- параллельные burst-процессы с большими независимыми stdout/stderr и bounded capture;
- deterministic flaky outcomes без timeout при фиксированных seed;
- timeout churn для зависающих flaky-процессов;
- остановку parent process вместе с spawned child process;
- остановку parent/child/grandchild process tree;
- long-running stream с медленным listener и backpressure;
- repeated child-spawn loop;
- параллельные mixed CPU/memory/output load-процессы;
- pooled line-session под смешанной нагрузкой успешных requests и request timeouts;
- pooled protocol-session под смешанной нагрузкой успешных requests и request timeouts с retire таймаутнувших workers.
