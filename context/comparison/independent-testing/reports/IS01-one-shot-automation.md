# IS01: One-shot automation with diagnostics

## Scenario

Сценарий: одноразовый запуск CLI вроде `git status --short`, `terraform validate`, `python --version`,
генератора кода или линтера.

Проверяемые свойства:

- direct argv без shell parsing;
- working directory;
- environment override;
- stdin input;
- bounded stdout и stderr;
- non-zero exit как наблюдаемый outcome, а не потерянная ошибка;
- timeout outcome с сохранением частичного вывода;
- диагностическая пригодность результата: exit code, timeout flag, truncation flags, elapsed/lifecycle observations.

Метод оценки: смотрел разрешенные инструкции, rubric, список сценариев, публичный API/source iCLI, локальные
comparison adapters и локальные signatures внешних библиотек. Не читал прежние comparison conclusions:
`context/comparison/results.md`, `context/comparison/qualitative-assessment.md`,
`context/decisions/ADR-0012-scenario-first-after-library-comparison.md`.

`OneShotOutcome`, `reportNonZero(...)` и `events` в snippets ниже считаются пользовательским DTO/reporting harness и
не входят в LOC. `BoundedOutput` входит в LOC у кандидатов, где библиотека не дает bounded capture как готовую
сценарную политику.

Минимальный helper, который нужен большинству внешних кандидатов:

```java
final class BoundedOutput extends OutputStream {
    private final byte[] buffer;
    private int size;
    private boolean truncated;

    BoundedOutput(int limit) {
        this.buffer = new byte[limit];
    }

    @Override
    public synchronized void write(int value) {
        write(new byte[] {(byte) value}, 0, 1);
    }

    @Override
    public synchronized void write(byte[] source, int offset, int length) {
        int available = buffer.length - size;
        if (available <= 0) {
            truncated = true;
            return;
        }
        int retained = Math.min(available, length);
        System.arraycopy(source, offset, buffer, size, retained);
        size += retained;
        truncated |= retained < length;
    }

    synchronized byte[] bytes() {
        return Arrays.copyOf(buffer, size);
    }

    synchronized boolean truncated() {
        return truncated;
    }
}
```

Helper LOC: 30.

## Implementations

### iCLI rewrite

```java
List<DiagnosticEvent> events = Collections.synchronizedList(new ArrayList<>());
CommandService service = CommandService.forCommand("tool")
        .withDiagnostics(DiagnosticsOptions.defaults().withListener(events::add));

CommandResult result = service.run(call -> call
        .args("validate", "--json")
        .workingDirectory(projectDir)
        .putEnvironment("TOOL_MODE", "ci")
        .input("payload\n")
        .capture(CapturePolicy.bounded(64 * 1024))
        .timeout(Duration.ofSeconds(2))
        .output(OutputMode.SEPARATE));

OneShotOutcome outcome = new OneShotOutcome(
        result.exitCode(),
        result.timedOut(),
        result.stdoutBytes(),
        result.stdoutTruncated(),
        result.stderrBytes(),
        result.stderrTruncated(),
        events);

if (!outcome.timedOut() && outcome.exitCode().orElseThrow() != 0) {
    reportNonZero(outcome);
}
```

LOC: 22

Status: implemented

API: 95

Docs: 88

Library: 86

Scenario: 90

Notes: Сценарий выражается напрямую через `run`: direct argv является default, `workingDirectory`, env override,
stdin, bounded capture, timeout и separate stdout/stderr находятся в одном builder. Non-zero exit не бросает по
умолчанию, а остается в `CommandResult`; timeout также возвращается как typed outcome с partial output. Diagnostics
имеют отдельный `DiagnosticsOptions` и redaction-friendly lifecycle events. Главный минус для library score: rewrite
еще `0.0.0-SNAPSHOT`, то есть maintenance story слабее, чем у зрелых внешних библиотек.

### JDK ProcessBuilder

```java
byte[] input = "payload\n".getBytes(StandardCharsets.UTF_8);
ProcessBuilder builder = new ProcessBuilder(List.of("tool", "validate", "--json"));
builder.directory(projectDir.toFile());
builder.environment().put("TOOL_MODE", "ci");
Process process = builder.start();

BoundedOutput stdout = new BoundedOutput(64 * 1024);
BoundedOutput stderr = new BoundedOutput(64 * 1024);
CompletableFuture<Void> stdin = CompletableFuture.runAsync(() -> {
    try (OutputStream stream = process.getOutputStream()) {
        stream.write(input);
    } catch (IOException exception) {
        throw new UncheckedIOException(exception);
    }
});
CompletableFuture<Void> stdoutPump = CompletableFuture.runAsync(() -> {
    try (InputStream stream = process.getInputStream()) {
        stream.transferTo(stdout);
    } catch (IOException exception) {
        throw new UncheckedIOException(exception);
    }
});
CompletableFuture<Void> stderrPump = CompletableFuture.runAsync(() -> {
    try (InputStream stream = process.getErrorStream()) {
        stream.transferTo(stderr);
    } catch (IOException exception) {
        throw new UncheckedIOException(exception);
    }
});

boolean exited = process.waitFor(2, TimeUnit.SECONDS);
boolean timedOut = !exited;
if (timedOut) {
    process.destroy();
    if (!process.waitFor(200, TimeUnit.MILLISECONDS)) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
    }
}
stdin.get(1, TimeUnit.SECONDS);
stdoutPump.get(1, TimeUnit.SECONDS);
stderrPump.get(1, TimeUnit.SECONDS);

OneShotOutcome outcome = new OneShotOutcome(
        timedOut ? OptionalInt.empty() : OptionalInt.of(process.exitValue()),
        timedOut,
        stdout.bytes(),
        stdout.truncated(),
        stderr.bytes(),
        stderr.truncated(),
        List.of());

if (!timedOut && process.exitValue() != 0) {
    reportNonZero(outcome);
}
```

LOC: 81 (51 scenario + 30 `BoundedOutput`)

Status: implemented

API: 58

Docs: 80

Library: 78

Scenario: 70

Notes: JDK дает самый надежный базовый substrate и прямой `argv`, `cwd`, env и streams без dependency risk. Но human
factor слабый: пользователь сам проектирует stdout/stderr pumps, bounded retention, stdin close, timeout escalation,
future cleanup и diagnostic shape. Non-zero exit нормально читается через `exitValue`, но timeout превращается в
ручной state machine. Документация стабильная, но не сценарная.

### Apache Commons Exec

```java
BoundedOutput stdout = new BoundedOutput(64 * 1024);
BoundedOutput stderr = new BoundedOutput(64 * 1024);
CommandLine command = new CommandLine("tool");
command.addArgument("validate", false);
command.addArgument("--json", false);

Map<String, String> environment = new HashMap<>(System.getenv());
environment.put("TOOL_MODE", "ci");
ExecuteWatchdog watchdog = ExecuteWatchdog.builder()
        .setTimeout(Duration.ofSeconds(2))
        .get();
DefaultExecutor executor = DefaultExecutor.builder()
        .setWorkingDirectory(projectDir.toFile())
        .setExecuteStreamHandler(new PumpStreamHandler(
                stdout,
                stderr,
                new ByteArrayInputStream("payload\n".getBytes(StandardCharsets.UTF_8))))
        .get();
executor.setExitValues(null);
executor.setWatchdog(watchdog);

int exit;
try {
    exit = executor.execute(command, environment);
} catch (ExecuteException exception) {
    exit = exception.getExitValue();
}
boolean timedOut = watchdog.killedProcess();
OneShotOutcome outcome = new OneShotOutcome(
        OptionalInt.of(exit),
        timedOut,
        stdout.bytes(),
        stdout.truncated(),
        stderr.bytes(),
        stderr.truncated(),
        List.of());

if (!timedOut && exit != 0) {
    reportNonZero(outcome);
}
```

LOC: 67 (37 scenario + 30 `BoundedOutput`)

Status: implemented

API: 74

Docs: 72

Library: 76

Scenario: 74

Notes: Commons Exec ближе к one-shot процессам, чем JDK: есть `DefaultExecutor`, `PumpStreamHandler`,
`ExecuteWatchdog`, `workingDirectory` и явная политика acceptable exit values. Основная когнитивная нагрузка остается у
caller: safe direct argv требует не использовать `CommandLine.parse`, env override лучше собирать явно, bounded capture
нужно писать самому, а timeout outcome извлекается через `watchdog.killedProcess()`. Зрелость неплохая, но API
исторически options-oriented и failure semantics надо знать заранее.

### ZeroTurnaround zt-exec

```java
BoundedOutput stdout = new BoundedOutput(64 * 1024);
BoundedOutput stderr = new BoundedOutput(64 * 1024);

OneShotOutcome outcome;
try {
    ProcessResult result = new ProcessExecutor()
            .command(List.of("tool", "validate", "--json"))
            .directory(projectDir.toFile())
            .environment("TOOL_MODE", "ci")
            .redirectInput(new ByteArrayInputStream("payload\n".getBytes(StandardCharsets.UTF_8)))
            .redirectOutput(stdout)
            .redirectError(stderr)
            .exitValueAny()
            .timeout(2, TimeUnit.SECONDS)
            .execute();
    outcome = new OneShotOutcome(
            OptionalInt.of(result.getExitValue()),
            false,
            stdout.bytes(),
            stdout.truncated(),
            stderr.bytes(),
            stderr.truncated(),
            List.of());
} catch (TimeoutException exception) {
    outcome = new OneShotOutcome(
            OptionalInt.empty(),
            true,
            stdout.bytes(),
            stdout.truncated(),
            stderr.bytes(),
            stderr.truncated(),
            List.of());
}

if (!outcome.timedOut() && outcome.exitCode().orElseThrow() != 0) {
    reportNonZero(outcome);
}
```

LOC: 65 (35 scenario + 30 `BoundedOutput`)

Status: implemented

API: 84

Docs: 76

Library: 78

Scenario: 80

Notes: Лучший fit среди внешних general-purpose библиотек. Fluent API хорошо выражает direct argv, cwd, env override,
stdin, separate streams, any exit value и timeout. Минусы: bounded capture не является встроенным result contract,
timeout приходит исключением, diagnostics надо строить вокруг вызова, а resource/stopper semantics нужно читать
отдельно. Для one-shot automation код заметно короче и понятнее, чем JDK/Commons Exec.

### NuProcess

```java
BoundedOutput stdout = new BoundedOutput(64 * 1024);
BoundedOutput stderr = new BoundedOutput(64 * 1024);
byte[] input = "payload\n".getBytes(StandardCharsets.UTF_8);

RunHandler handler = new RunHandler(input, stdout, stderr);
NuProcessBuilder builder = new NuProcessBuilder(List.of("tool", "validate", "--json"));
builder.setCwd(projectDir);
builder.environment().put("TOOL_MODE", "ci");
builder.setProcessListener(handler);
NuProcess process = builder.start();

int exit = process.waitFor(2, TimeUnit.SECONDS);
boolean timedOut = process.isRunning();
if (timedOut) {
    process.destroy(true);
    process.waitFor(5, TimeUnit.SECONDS);
}

OneShotOutcome outcome = new OneShotOutcome(
        timedOut ? OptionalInt.empty() : OptionalInt.of(exit),
        timedOut,
        stdout.bytes(),
        stdout.truncated(),
        stderr.bytes(),
        stderr.truncated(),
        List.of());

final class RunHandler extends NuAbstractProcessHandler {
    private final byte[] stdin;
    private final BoundedOutput stdout;
    private final BoundedOutput stderr;
    private NuProcess process;
    private boolean wrote;

    RunHandler(byte[] stdin, BoundedOutput stdout, BoundedOutput stderr) {
        this.stdin = stdin;
        this.stdout = stdout;
        this.stderr = stderr;
    }

    @Override
    public void onStart(NuProcess process) {
        this.process = process;
        if (stdin.length == 0) {
            process.closeStdin(false);
        } else {
            process.wantWrite();
        }
    }

    @Override
    public boolean onStdinReady(ByteBuffer buffer) {
        if (!wrote) {
            buffer.put(stdin);
            buffer.flip();
            wrote = true;
        }
        process.closeStdin(false);
        return false;
    }

    @Override
    public void onStdout(ByteBuffer buffer, boolean closed) {
        capture(buffer, stdout);
    }

    @Override
    public void onStderr(ByteBuffer buffer, boolean closed) {
        capture(buffer, stderr);
    }

    private void capture(ByteBuffer buffer, OutputStream output) {
        if (!buffer.hasRemaining()) {
            return;
        }
        byte[] chunk = new byte[buffer.remaining()];
        buffer.get(chunk);
        try {
            output.write(chunk);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
```

LOC: 104 (74 scenario/handler + 30 `BoundedOutput`)

Status: implemented

API: 52

Docs: 58

Library: 72

Scenario: 61

Notes: NuProcess пригоден, но это не ergonomic one-shot API. Он силен как low-level non-blocking process I/O, поэтому
для IS01 пользователь пишет callback handler, stdin write state, bounded capture, timeout mapping и outcome wrapper.
Пример выше еще упрощает stdin: production code должен учитывать input больше одного `ByteBuffer`. Library quality
лучше, чем API score, потому что модель полезна для high-throughput/async задач, но для human-factor one-shot это
слишком низкий уровень.

### Pty4J

```java
BoundedOutput terminalOutput = new BoundedOutput(64 * 1024);
Map<String, String> environment = new HashMap<>(System.getenv());
environment.put("TOOL_MODE", "ci");
PtyProcess process = new PtyProcessBuilder()
        .setCommand(new String[] {"tool", "validate", "--json"})
        .setDirectory(projectDir.toString())
        .setEnvironment(environment)
        .setRedirectErrorStream(true)
        .start();

CompletableFuture<Void> stdin = CompletableFuture.runAsync(() -> {
    try (OutputStream stream = process.getOutputStream()) {
        stream.write("payload\n".getBytes(StandardCharsets.UTF_8));
    } catch (IOException exception) {
        throw new UncheckedIOException(exception);
    }
});
CompletableFuture<Void> outputPump = CompletableFuture.runAsync(() -> {
    try (InputStream stream = process.getInputStream()) {
        stream.transferTo(terminalOutput);
    } catch (IOException exception) {
        throw new UncheckedIOException(exception);
    }
});

boolean exited = process.waitFor(2, TimeUnit.SECONDS);
boolean timedOut = !exited;
if (timedOut) {
    process.destroyForcibly();
    process.waitFor(5, TimeUnit.SECONDS);
}
stdin.get(1, TimeUnit.SECONDS);
outputPump.get(1, TimeUnit.SECONDS);

OneShotOutcome outcome = new OneShotOutcome(
        timedOut ? OptionalInt.empty() : OptionalInt.of(process.exitValue()),
        timedOut,
        terminalOutput.bytes(),
        terminalOutput.truncated(),
        new byte[0],
        false,
        List.of("stderr is not independently captured in this PTY workaround"));
```

LOC: 69 (39 workaround + 30 `BoundedOutput`)

Status: workaround

API: 35

Docs: 45

Library: 55

Scenario: 45

Notes: Pty4J предназначен для terminal-required процессов, а не обычной pipe-based one-shot automation. Direct argv,
cwd, env и stdin возможны, но stdout/stderr превращаются в terminal stream, где stderr не является самостоятельным
bounded channel. Timeout и diagnostics полностью ручные. Для IS01 это workaround с semantic mismatch: terminal control
может менять вывод, echo, line endings и buffering.

### ExpectIt

```java
BoundedEchoOutput stdout = new BoundedEchoOutput(64 * 1024);
BoundedOutput stderr = new BoundedOutput(64 * 1024);
ProcessBuilder builder = new ProcessBuilder(List.of("tool", "validate", "--json"));
builder.directory(projectDir.toFile());
builder.environment().put("TOOL_MODE", "ci");
Process process = builder.start();

CompletableFuture<Void> stderrPump = CompletableFuture.runAsync(() -> {
    try (InputStream stream = process.getErrorStream()) {
        stream.transferTo(stderr);
    } catch (IOException exception) {
        throw new UncheckedIOException(exception);
    }
});

boolean timedOut = false;
try (Expect expect = new ExpectBuilder()
        .withInputs(process.getInputStream())
        .withOutput(process.getOutputStream())
        .withEchoOutput(stdout)
        .withTimeout(2, TimeUnit.SECONDS)
        .withExceptionOnFailure()
        .build()) {
    expect.send("payload\n");
    process.getOutputStream().close();
    expect.expect(Matchers.eof());
} catch (IOException | RuntimeException exception) {
    timedOut = true;
    process.destroyForcibly();
}

boolean exited = process.waitFor(2, TimeUnit.SECONDS);
timedOut |= !exited;
if (!exited) {
    process.destroyForcibly();
    process.waitFor(5, TimeUnit.SECONDS);
}
stderrPump.get(1, TimeUnit.SECONDS);

OneShotOutcome outcome = new OneShotOutcome(
        timedOut ? OptionalInt.empty() : OptionalInt.of(process.exitValue()),
        timedOut,
        stdout.bytes(),
        stdout.truncated(),
        stderr.bytes(),
        stderr.truncated(),
        List.of("ProcessBuilder owns argv/cwd/env/process timeout"));

final class BoundedEchoOutput implements EchoOutput {
    private final StringBuilder text = new StringBuilder();
    private final int limit;
    private boolean truncated;

    BoundedEchoOutput(int limit) {
        this.limit = limit;
    }

    @Override
    public synchronized void onReceive(int input, String chunk) {
        int available = limit - text.length();
        if (available <= 0) {
            truncated = true;
            return;
        }
        text.append(chunk, 0, Math.min(available, chunk.length()));
        truncated |= chunk.length() > available;
    }

    @Override
    public void onSend(String chunk) {}

    synchronized byte[] bytes() {
        return text.toString().getBytes(StandardCharsets.UTF_8);
    }

    synchronized boolean truncated() {
        return truncated;
    }
}
```

LOC: 99 (69 workaround + 30 `BoundedOutput`)

Status: workaround

API: 28

Docs: 50

Library: 50

Scenario: 41

Notes: ExpectIt не является process launcher и не является one-shot runner. Direct argv, cwd, env, process timeout и
stderr приходится брать из `ProcessBuilder`; ExpectIt только читает stdout-like input, пишет stdin и умеет ждать matcher
или EOF. Bounded stdout через `EchoOutput` является text-oriented workaround, не byte-accurate capture policy.
Нормальная область ExpectIt — prompt automation, а для IS01 он добавляет слой без владения нужными инвариантами.

## Summary

Лучший fit: iCLI rewrite. Он единственный выражает IS01 как единый сценарий с typed result, timeout flag, truncation
flags и diagnostics hook без ручного process harness.

Самый короткий код: iCLI rewrite, 22 LOC. Среди внешних general-purpose кандидатов самый короткий и читаемый путь у
zt-exec, но он все равно требует пользовательского bounded capture и ручного превращения `TimeoutException` в outcome.

Самый надежный contract: iCLI rewrite для сценарного контракта, JDK `ProcessBuilder` для базовой платформенной
стабильности. Разница в том, что JDK стабилен как primitive, но не владеет инвариантами IS01; их должен собрать caller.

Итоговая таблица:

| Candidate | LOC | Status | API | Docs | Library | Scenario |
| --- | ---: | --- | ---: | ---: | ---: | ---: |
| iCLI rewrite | 22 | implemented | 95 | 88 | 86 | 90 |
| JDK ProcessBuilder | 81 | implemented | 58 | 80 | 78 | 70 |
| Apache Commons Exec | 67 | implemented | 74 | 72 | 76 | 74 |
| ZeroTurnaround zt-exec | 65 | implemented | 84 | 76 | 78 | 80 |
| NuProcess | 104 | implemented | 52 | 58 | 72 | 61 |
| Pty4J | 69 | workaround | 35 | 45 | 55 | 45 |
| ExpectIt | 99 | workaround | 28 | 50 | 50 | 41 |

Unsupported/workaround conclusions:

- Pty4J: can launch a process, but IS01 requires pipe-like separate bounded stdout/stderr; PTY terminal output is the
  wrong abstraction.
- ExpectIt: useful for prompt automation, but process launch, stderr, timeout outcome and most diagnostics are outside
  its scope.
