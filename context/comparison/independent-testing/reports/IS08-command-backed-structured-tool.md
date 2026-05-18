# IS08: Command-backed structured tool

## Scenario

Агент или server-side tool adapter вызывает внешний CLI и должен вернуть структурированное observation, а не передать
stdout как исполняемую инструкцию. Минимальный контракт сценария:

- success result возвращает typed/JSON payload;
- failure возвращает structured error с machine-readable code;
- stdout/stderr или transcript удерживаются bounded;
- diagnostics redaction-safe: без raw argv values, env values, stdin и stdout/stderr excerpts по умолчанию;
- граница между agent harness и CLI является JSON/JSONL или другим явным structured protocol.

В sketches ниже `request` - входной JSON payload, `command` - direct argv внешнего CLI, `limitBytes` - лимит удержания
stdout/stderr. Для внешних библиотек используются application-local helpers `ToolObservation`, `BoundedOutput`,
`JsonBoundary` и `redacted(...)`; это не API кандидатов. LOC считает видимый core glue вокруг кандидата и поэтому
скорее благосклонен к внешним библиотекам: reusable helper implementations добавили бы заметный общий код в реальном
adapter layer.

## Implementations

### iCLI rewrite

```java
CommandService service = new CommandService(
                CommandSpec.builder(command.getFirst())
                        .args(command.subList(1, command.size()))
                        .build(),
                RunOptions.defaults())
        .withDiagnostics(diagnosticsOptions);

CommandBackedTool<JsonValue, JsonValue> tool = CommandBackedTool.of(input -> {
    CommandResult result = service.run(call -> call.capture(CapturePolicy.bounded(limitBytes))
            .timeout(Duration.ofSeconds(2))
            .input(CommandInput.utf8(JsonLines.frame(input))));
    if (!result.succeeded()) {
        throw result.toException();
    }
    return JsonLines.parseLine(result.stdout().stripTrailing());
});

ToolCallResult<JsonValue> result = tool.call(request);
JsonValue diagnostics = JsonValue.object(Map.of(
        "boundary", JsonValue.string("jsonl"),
        "diagnosticsRedacted", JsonValue.bool(true)));
return result.succeeded()
        ? JsonValue.object(Map.of(
                "ok", JsonValue.bool(true),
                "data", result.value().orElseThrow(),
                "diagnostics", diagnostics))
        : JsonValue.object(Map.of(
                "ok", JsonValue.bool(false),
                "error", result.error().orElseThrow().toJson(),
                "diagnostics", diagnostics));
```

LOC: 26
Status: implemented
API: 88
Docs: 84
Library: 89
Scenario: 88
Notes: Лучший fit среди кандидатов: core `run` уже владеет direct argv, timeout, bounded stdout/stderr и process cleanup,
а optional `:icli-integrations` добавляет `CommandBackedTool`, `ToolCallResult`, `CliAdapterError`, `JsonValue` и JSONL
helpers. Structured failure не требует выбрасывать raw exception в agent harness: `CommandResult.toException()` затем
мапится в redaction-friendly `CliAdapterError` с `exitCode`, `timedOut` и truncation flags. Diagnostics contract
отдельно запрещает raw argv/env/output в events. Главный human-factor минус: one-shot JSON tool still needs a small
adapter lambda; нет одной публичной `runJsonTool(...)` convenience-обертки.

### JDK ProcessBuilder

```java
ProcessBuilder builder = new ProcessBuilder(command);
builder.directory(workDir.toFile());
builder.environment().putAll(environment);
Process process = builder.start();

BoundedOutput stdout = new BoundedOutput(limitBytes);
BoundedOutput stderr = new BoundedOutput(limitBytes);
CompletableFuture<Void> stdoutPump =
        CompletableFuture.runAsync(() -> stdout.drain(process.getInputStream()));
CompletableFuture<Void> stderrPump =
        CompletableFuture.runAsync(() -> stderr.drain(process.getErrorStream()));

try (OutputStream stdin = process.getOutputStream()) {
    stdin.write(JsonBoundary.frame(request));
}

boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
if (!exited) {
    process.destroyForcibly();
    process.waitFor(killTimeout.toMillis(), TimeUnit.MILLISECONDS);
}
stdoutPump.get(1, TimeUnit.SECONDS);
stderrPump.get(1, TimeUnit.SECONDS);

JsonValue diagnostics = redacted(command, stdout.truncated(), stderr.truncated(), "jsonl");
if (!exited) {
    return ToolObservation.failure("command_timeout", "Command timed out", diagnostics);
}
if (process.exitValue() != 0) {
    return ToolObservation.failure("command_failed", "Command exited unsuccessfully", diagnostics);
}
return ToolObservation.success(JsonBoundary.parseSingleLine(stdout.text()), diagnostics);
```

LOC: 30
Status: workaround
API: 56
Docs: 70
Library: 76
Scenario: 67
Notes: `ProcessBuilder` - надежный baseline запуска процесса, но IS08 почти целиком находится выше JDK API. Пользователь
сам владеет concurrent drain, bounded buffers, timeout cleanup, JSON framing/parsing, non-zero mapping, redaction policy
и structured result type. Failure semantics легко испортить: забыть stderr pump, вызвать `exitValue()` после timeout,
залогировать raw command или вернуть stdout как plain string. Зато maintenance story у JDK сильная, переносимость
понятная, и для команды с готовым internal harness это самый defensible низкоуровневый fallback.

### Apache Commons Exec

```java
BoundedOutput stdout = new BoundedOutput(limitBytes);
BoundedOutput stderr = new BoundedOutput(limitBytes);
ExecuteWatchdog watchdog = ExecuteWatchdog.builder()
        .setTimeout(timeout)
        .get();
DefaultExecutor executor = DefaultExecutor.builder()
        .setExecuteStreamHandler(new PumpStreamHandler(
                stdout, stderr, new ByteArrayInputStream(JsonBoundary.frame(request))))
        .setWorkingDirectory(workDir.toFile())
        .get();
executor.setExitValues(null);
executor.setWatchdog(watchdog);

int exitCode;
try {
    exitCode = executor.execute(toCommandLine(command), environment);
} catch (ExecuteException failure) {
    exitCode = failure.getExitValue();
}

JsonValue diagnostics = redacted(command, stdout.truncated(), stderr.truncated(), "jsonl");
if (watchdog.killedProcess()) {
    return ToolObservation.failure("command_timeout", "Command timed out", diagnostics);
}
if (exitCode != 0) {
    return ToolObservation.failure("command_failed", "Command exited unsuccessfully", diagnostics);
}
return ToolObservation.success(JsonBoundary.parseSingleLine(stdout.text()), diagnostics);
```

LOC: 28
Status: workaround
API: 65
Docs: 72
Library: 77
Scenario: 71
Notes: Commons Exec лучше JDK закрывает process supervision ergonomics: `PumpStreamHandler`, `ExecuteWatchdog`,
`DefaultExecutor` и explicit exit values уменьшают риск забыть stream pumping. Но structured tool boundary не является
понятием библиотеки: JSON protocol, result envelope, redaction-safe diagnostics и stderr/stdout truncation policy
остаются application code. `CommandLine.addArgument(..., false)` также требует дисциплины, чтобы не сломать direct argv
semantics. Для enterprise-style one-shot execution это зрелый workaround, но не scenario-level contract.

### ZeroTurnaround zt-exec

```java
BoundedOutput stdout = new BoundedOutput(limitBytes);
BoundedOutput stderr = new BoundedOutput(limitBytes);
ProcessExecutor executor = new ProcessExecutor()
        .command(command)
        .directory(workDir.toFile())
        .environment(environment)
        .redirectInput(new ByteArrayInputStream(JsonBoundary.frame(request)))
        .redirectOutput(stdout)
        .redirectError(stderr)
        .exitValueAny()
        .timeout(timeout.toMillis(), TimeUnit.MILLISECONDS);

try {
    ProcessResult processResult = executor.execute();
    JsonValue diagnostics = redacted(command, stdout.truncated(), stderr.truncated(), "jsonl");
    if (processResult.getExitValue() != 0) {
        return ToolObservation.failure("command_failed", "Command exited unsuccessfully", diagnostics);
    }
    return ToolObservation.success(JsonBoundary.parseSingleLine(stdout.text()), diagnostics);
} catch (TimeoutException timeoutFailure) {
    JsonValue diagnostics = redacted(command, stdout.truncated(), stderr.truncated(), "jsonl");
    return ToolObservation.failure("command_timeout", "Command timed out", diagnostics);
}
```

LOC: 23
Status: workaround
API: 72
Docs: 72
Library: 74
Scenario: 73
Notes: Самый короткий внешний one-shot sketch. Fluent API хорошо выражает command, cwd/env, stdin, stdout/stderr
redirects, exit-value policy и timeout. Но bounded output обеспечивается custom `OutputStream`, structured
success/error envelope полностью внешний, а redaction зависит от того, как пользователь настроит logging/message
logger и свой `ToolObservation`. Это удобный process wrapper, не adapter framework для untrusted tool observations.

### NuProcess

```java
BoundedOutput stdout = new BoundedOutput(limitBytes);
BoundedOutput stderr = new BoundedOutput(limitBytes);
JsonNuHandler handler = new JsonNuHandler(JsonBoundary.frame(request), stdout, stderr);
NuProcessBuilder builder = new NuProcessBuilder(command);
builder.setCwd(workDir);
builder.environment().putAll(environment);
builder.setProcessListener(handler);

NuProcess process = builder.start();
int exitCode = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
boolean timedOut = process.isRunning();
if (timedOut) {
    process.destroy(true);
    process.waitFor(killTimeout.toMillis(), TimeUnit.MILLISECONDS);
}

JsonValue diagnostics = redacted(command, stdout.truncated(), stderr.truncated(), "jsonl");
if (timedOut) {
    return ToolObservation.failure("command_timeout", "Command timed out", diagnostics);
}
if (exitCode != 0) {
    return ToolObservation.failure("command_failed", "Command exited unsuccessfully", diagnostics);
}
return ToolObservation.success(JsonBoundary.parseSingleLine(stdout.text()), diagnostics);

final class JsonNuHandler extends NuAbstractProcessHandler {
    private final byte[] stdin;
    private final BoundedOutput stdout;
    private final BoundedOutput stderr;
    private NuProcess process;
    private boolean wrote;

    JsonNuHandler(byte[] stdin, BoundedOutput stdout, BoundedOutput stderr) {
        this.stdin = stdin;
        this.stdout = stdout;
        this.stderr = stderr;
    }

    @Override public void onStart(NuProcess process) {
        this.process = process;
        process.wantWrite();
    }

    @Override public boolean onStdinReady(ByteBuffer buffer) {
        if (!wrote) {
            buffer.put(stdin);
            buffer.flip();
            wrote = true;
        }
        process.closeStdin(false);
        return false;
    }

    @Override public void onStdout(ByteBuffer buffer, boolean closed) {
        stdout.writeRemaining(buffer);
    }

    @Override public void onStderr(ByteBuffer buffer, boolean closed) {
        stderr.writeRemaining(buffer);
    }
}
```

LOC: 58
Status: workaround
API: 53
Docs: 60
Library: 71
Scenario: 62
Notes: NuProcess полезен, когда главная задача - non-blocking I/O и callback throughput. Для IS08 эта модель скорее
увеличивает cognitive load: пользователь проектирует stateful handler, stdin close semantics, bounded capture,
exception propagation из callbacks, JSON framing и result mapping. Библиотека зрелая для своего narrow scope, но
structured tool contract ей не принадлежит. Human-factor риск выше, чем у JDK/Commons/zt-exec: корректность распределена
между callback state и post-process mapping.

### Pty4J

```java
Map<String, String> env = new HashMap<>(environment);
env.putIfAbsent("TERM", "xterm");
PtyProcess process = new PtyProcessBuilder()
        .setCommand(command.toArray(String[]::new))
        .setDirectory(workDir.toString())
        .setEnvironment(env)
        .setInitialColumns(120)
        .setInitialRows(40)
        .start();

BoundedOutput terminal = new BoundedOutput(limitBytes);
CompletableFuture<Void> pump =
        CompletableFuture.runAsync(() -> terminal.drain(process.getInputStream()));
try (OutputStream stdin = process.getOutputStream()) {
    stdin.write(JsonBoundary.frame(request));
}

boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
if (!exited) {
    process.destroyForcibly();
    process.waitFor(killTimeout.toMillis(), TimeUnit.MILLISECONDS);
}
pump.get(1, TimeUnit.SECONDS);

JsonValue diagnostics = redacted(command, terminal.truncated(), false, "jsonl-over-pty");
if (!exited) {
    return ToolObservation.failure("command_timeout", "Command timed out", diagnostics);
}
if (process.exitValue() != 0) {
    return ToolObservation.failure("command_failed", "Command exited unsuccessfully", diagnostics);
}
return ToolObservation.success(JsonBoundary.parseSingleLine(stripTerminalNoise(terminal.text())), diagnostics);
```

LOC: 30
Status: workaround
API: 35
Docs: 52
Library: 57
Scenario: 47
Notes: Pty4J умеет запускать процесс в pseudo-terminal, но IS08 не требует terminal capability. Для structured tool это
неестественный transport: stderr attribution обычно теряется или зависит от redirect mode, TTY может менять поведение
CLI, output может включать terminal noise/control sequences, а JSONL boundary становится менее надежной. Redaction,
bounded output, timeout interpretation и structured error envelope полностью остаются custom adapter code. Подходит
только если конкретный tool command сам требует PTY; как общий command-backed structured tool layer это плохой fit.

### ExpectIt

```java
Process process = new ProcessBuilder(command)
        .directory(workDir.toFile())
        .start();
BoundedOutput stderr = new BoundedOutput(limitBytes);
CompletableFuture<Void> stderrPump =
        CompletableFuture.runAsync(() -> stderr.drain(process.getErrorStream()));

try (Expect expect = new ExpectBuilder()
        .withInputs(process.getInputStream())
        .withOutput(process.getOutputStream())
        .withTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
        .withBufferSize(limitBytes)
        .withExceptionOnFailure()
        .build()) {
    expect.sendLine(JsonBoundary.line(request));
    Result matched = expect.expect(Matchers.regexp("\\{.*}\\r?\\n"));
    JsonValue diagnostics = redacted(command, false, stderr.truncated(), "jsonl-over-expect");
    return ToolObservation.success(JsonBoundary.parseSingleLine(matched.getInput().strip()), diagnostics);
} catch (Exception failure) {
    JsonValue diagnostics = redacted(command, false, stderr.truncated(), "jsonl-over-expect");
    return ToolObservation.failure("protocol_error", "CLI protocol exchange failed", diagnostics);
} finally {
    process.destroyForcibly();
    stderrPump.get(1, TimeUnit.SECONDS);
}
```

LOC: 25
Status: workaround
API: 38
Docs: 58
Library: 52
Scenario: 48
Notes: ExpectIt is a matcher/automation layer over streams, not a process runtime. Sketch выше фактически зависит от
JDK `ProcessBuilder` для launch, stderr drain и cleanup; ExpectIt отвечает только за JSONL-style request/response
matching. `withBufferSize(limitBytes)` помогает ограничить matcher buffer, но это не полноценная bounded stdout/stderr
result policy. Non-zero exit, process timeout, redaction-safe diagnostics и structured error taxonomy нужно строить
снаружи. Для prompt automation это полезная библиотека, для IS08 - только вспомогательный matcher внутри более крупного
adapter harness.

## Summary

| Candidate | LOC | Status | API | Docs | Library | Scenario |
| --- | ---: | --- | ---: | ---: | ---: | ---: |
| iCLI rewrite | 26 | implemented | 88 | 84 | 89 | 88 |
| JDK ProcessBuilder | 30 | workaround | 56 | 70 | 76 | 67 |
| Apache Commons Exec | 28 | workaround | 65 | 72 | 77 | 71 |
| ZeroTurnaround zt-exec | 23 | workaround | 72 | 72 | 74 | 73 |
| NuProcess | 58 | workaround | 53 | 60 | 71 | 62 |
| Pty4J | 30 | workaround | 35 | 52 | 57 | 47 |
| ExpectIt | 25 | workaround | 38 | 58 | 52 | 48 |

Лучший fit: iCLI rewrite, потому что structured tool result, redaction-safe adapter errors, bounded process output и JSON
helpers существуют в том же public surface, что и process сценарии. Это единственный кандидат, где значимая часть
IS08-контракта принадлежит библиотеке, а не пользовательскому harness.

Самый короткий внешний код: zt-exec. Его fluent one-shot API хорошо сокращает запуск и timeout, но structured boundary
и safety envelope все равно внешние.

Самый надежный low-level contract: JDK `ProcessBuilder`, если команда готова владеть собственным adapter framework.
Он менее удобен, зато его lifecycle semantics и support story наиболее предсказуемы.

Самые слабые fits: Pty4J и ExpectIt. Они решают соседние задачи - terminal transport и expect-style matching. Для IS08
они добавляют ограничения вместо сценарных гарантий, если только конкретный CLI не требует PTY или prompt protocol.
