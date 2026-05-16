# Поведенческие проверки process execution

## Назначение

Этот файл задает минимальный eval-набор для реализации iCLI. Каждый сценарий должен стать тестом или fixture-case
до того, как соответствующее поведение будет считаться готовым.

## One-shot команды

- Команда завершается с exit code `0`, stdout захвачен полностью.
- Команда завершается с non-zero exit code, stdout/stderr доступны для диагностики.
- Большой stdout обрезается по bounded policy и выставляет truncation flag.
- Большой stderr обрезается независимо от stdout.
- Stdout/stderr можно объединить в один поток.
- Команда получает working directory.
- Команда получает environment override.
- Unicode output декодируется заданным charset.
- Timeout приводит к shutdown policy и понятному результату или исключению.
- Interrupted wait не теряет interrupt status.

## Shell и argv

- По умолчанию команда запускается direct argv без shell.
- Shell mode включается явно.
- Аргументы с пробелами не ломают direct argv запуск.
- Shell-specific поведение не появляется случайно в direct mode.

## Streaming

- Stdout и stderr дренируются параллельно.
- Процесс, который пишет много в stderr и мало в stdout, не зависает.
- Streaming listener получает chunks до завершения процесса.
- Медленный listener не должен бесконечно раздувать память.

## Interactive session

- Session открывается и закрывается без утечки процесса.
- `sendLine` отправляет строку и flush.
- `closeStdin` корректно сигнализирует EOF.
- `onExit` завершается после выхода процесса.
- Idle timeout закрывает зависшую session.
- Ctrl+C/interrupt поведение проверено хотя бы для pipe-compatible fixture.

## Line-oriented workflow

- Один input дает один response.
- Параллельные вызовы не перемешивают stdin/stdout.
- Response decoder сохраняет значимые переносы строк или явно документирует нормализацию.
- Timeout ожидания response дает bounded transcript.

## Expect helper

- Literal match.
- Regex match.
- Timeout with transcript.
- EOF before expected output.
- Отправка строки после match.

## PTY

PTY не обязан быть первым реализованным транспортом, но до релиза должны быть проверки:

- command can request terminal;
- PTY fallback или unsupported behavior explicit;
- PTY session can send and receive text;
- terminal-required fixture или real REPL проходит под PTY.

## Release gate

Первый release candidate не готов, пока one-shot, streaming, timeout и базовая session группа не покрыты тестами.
