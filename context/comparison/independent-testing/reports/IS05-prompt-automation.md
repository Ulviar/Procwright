# IS05: Prompt automation

## Scenario

Нужно автоматизировать prompt-oriented CLI: дождаться prompt text или regex, отправить ответ, дождаться итогового
признака результата, сохранить bounded transcript и не раскрывать чувствительные send/expect values без явного
opt-in пользователя. Типичный пример: installer prompt, debugger prompt, local login-like prompt или
`ssh-keygen` confirmation flow.

Для sketches ниже предполагаются:

- `List<String> command`;
- `Pattern prompt`;
- `String answer`;
- `Pattern done`;
- `Duration timeout`;
- `PromptRun` как маленький DTO с exit/result и transcript.

LOC считает nonblank noncomment core lines без imports, fixture и package declaration. Если для библиотеки нужен
ручной matcher/transcript helper, он входит в LOC, потому что это не тестовый harness, а пользовательский код сценария.

## Implementations

### iCLI rewrite

```java
CommandSpec spec = CommandSpec.builder(command.getFirst())
        .args(command.subList(1, command.size()))
        .build();
CommandService service = new CommandService(spec, RunOptions.defaults());
ExpectOptions options = ExpectOptions.defaults()
        .withTimeout(timeout)
        .withTranscriptLimit(16 * 1024)
        .withOutputFilter(text -> text.replace(answer, "<redacted>"));
try (Session session = service.interactive(call -> {
            call.idleTimeout(timeout);
            call.terminal(TerminalPolicy.AUTO);
        });
        Expect expect = session.expect(options)) {
    expect.expectRegex(prompt);
    expect.sendLine(answer);
    expect.expectRegex(done);
    SessionExit exit = session.onExit().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
    return new PromptRun(exit, expect.transcript());
}
```

LOC: 19
Status: implemented
API: 91
Docs: 86
Library: 84
Scenario: 87
Notes: Сценарий выражен через `interactive` + `Expect`, без ручных pump threads. `ExpectOptions` явно владеет timeout,
bounded transcript, match buffer, charset, output filter и redacted action values по умолчанию. Failure semantics
разделяют `TIMEOUT`, `EOF`, `CLOSED`, `FAILURE`; transcript доступен в `ExpectException`. Минус: это не один top-level
`CommandService.expect(...)`, пользователь все еще открывает `Session` и затем оборачивает ее helper-ом. Библиотека
пока `0.0.0-SNAPSHOT`, поэтому maturity ниже, чем у стандартного JDK или давно опубликованных библиотек.

### JDK ProcessBuilder

```java
Process process = new ProcessBuilder(command).start();
StringBuilder output = new StringBuilder();
BoundedTranscript transcript = new BoundedTranscript(16 * 1024, answer);
CompletableFuture<Void> stdout = CompletableFuture.runAsync(
        () -> pump(process.getInputStream(), "stdout", output, transcript));
CompletableFuture<Void> stderr = CompletableFuture.runAsync(
        () -> pump(process.getErrorStream(), "stderr", null, transcript));
try (OutputStream stdin = process.getOutputStream()) {
    waitFor(output, prompt, timeout, transcript);
    transcript.action("send line: <redacted>");
    stdin.write((answer + "\n").getBytes(StandardCharsets.UTF_8));
    stdin.flush();
    waitFor(output, done, timeout, transcript);
}
boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
if (!exited) {
    process.destroyForcibly();
    process.waitFor(5, TimeUnit.SECONDS);
}
stdout.get(1, TimeUnit.SECONDS);
stderr.get(1, TimeUnit.SECONDS);
return new PromptRun(process.exitValue(), transcript.snapshot());

static void waitFor(StringBuilder output, Pattern pattern, Duration timeout, BoundedTranscript transcript)
        throws InterruptedException, TimeoutException {
    long deadline = System.nanoTime() + timeout.toNanos();
    transcript.action("expect regex: <redacted>");
    synchronized (output) {
        while (!pattern.matcher(output).find()) {
            long millis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (millis <= 0) {
                throw new TimeoutException("expected output not found");
            }
            output.wait(Math.max(1, millis));
        }
    }
}

static void pump(InputStream input, String source, StringBuilder matchable, BoundedTranscript transcript) {
    try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
        char[] buffer = new char[1024];
        int count;
        while ((count = reader.read(buffer)) >= 0) {
            String chunk = transcript.stream(source, new String(buffer, 0, count));
            if (matchable != null) {
                synchronized (matchable) {
                    matchable.append(chunk);
                    matchable.notifyAll();
                }
            }
        }
    } catch (IOException exception) {
        throw new UncheckedIOException(exception);
    }
}
```

LOC: 53
Status: workaround
API: 52
Docs: 75
Library: 86
Scenario: 70
Notes: `ProcessBuilder` надежен как стандартная база запуска процессов, но IS05 почти целиком приходится проектировать
самому: concurrent stdout/stderr drain, wait loop, timeout, destroy path, transcript bounds, redaction, EOF/failure
classification. Документация JDK хорошо описывает streams, environment, redirects и `waitFor`, но не дает
scenario-level expect contract. Риск human-factor высокий: легко забыть stderr drain, зависнуть на pipe buffer,
случайно записать secret в transcript или вызвать `exitValue()` после timeout до завершения cleanup.

### Apache Commons Exec

```java
CommandLine line = new CommandLine(command.getFirst());
command.stream().skip(1).forEach(arg -> line.addArgument(arg, false));
PromptStreamHandler streams = new PromptStreamHandler(prompt, answer, done, timeout);
ExecuteWatchdog watchdog = ExecuteWatchdog.builder().setTimeout(timeout).get();
DefaultExecuteResultHandler result = new DefaultExecuteResultHandler();
DefaultExecutor executor = DefaultExecutor.builder()
        .setExecuteStreamHandler(streams)
        .get();
executor.setExitValues(null);
executor.setWatchdog(watchdog);
executor.execute(line, result);
if (!streams.awaitDone(timeout)) {
    watchdog.destroyProcess();
}
result.waitFor(timeout.toMillis() + 1000);
if (watchdog.killedProcess()) {
    throw new TimeoutException("process killed by watchdog");
}
return new PromptRun(result.getExitValue(), streams.transcript());

final class PromptStreamHandler implements ExecuteStreamHandler {
    private final CountDownLatch doneLatch = new CountDownLatch(1);
    private final Pattern prompt;
    private final String answer;
    private final Pattern done;
    private final BoundedTranscript transcript;
    private OutputStream stdin;
    private InputStream stdout;
    private InputStream stderr;
    private StringBuilder output = new StringBuilder();
    private boolean answerSent;

    PromptStreamHandler(Pattern prompt, String answer, Pattern done, Duration timeout) {
        this.prompt = prompt;
        this.answer = answer;
        this.done = done;
        this.transcript = new BoundedTranscript(16 * 1024, answer);
    }

    public void setProcessInputStream(OutputStream stream) {
        stdin = stream;
    }

    public void setProcessOutputStream(InputStream stream) {
        stdout = stream;
    }

    public void setProcessErrorStream(InputStream stream) {
        stderr = stream;
    }

    public void start() {
        Thread.ofVirtual().start(() -> pump(stdout, "stdout", true));
        Thread.ofVirtual().start(() -> pump(stderr, "stderr", false));
    }

    public void stop() throws IOException {
        stdin.close();
    }

    boolean awaitDone(Duration timeout) throws InterruptedException {
        return doneLatch.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    LineTranscript transcript() {
        return transcript.snapshot();
    }

    private void pump(InputStream input, String source, boolean matchable) {
        try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            char[] buffer = new char[1024];
            int count;
            while ((count = reader.read(buffer)) >= 0) {
                String chunk = transcript.stream(source, new String(buffer, 0, count));
                if (matchable) {
                    output.append(chunk);
                    if (!answerSent && prompt.matcher(output).find()) {
                        transcript.action("send line: <redacted>");
                        stdin.write((answer + "\n").getBytes(StandardCharsets.UTF_8));
                        stdin.flush();
                        answerSent = true;
                    }
                    if (done.matcher(output).find()) {
                        doneLatch.countDown();
                    }
                }
            }
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
```

LOC: 82
Status: workaround
API: 44
Docs: 78
Library: 68
Scenario: 60
Notes: Commons Exec дает mature process execution, `ExecuteWatchdog`, `DefaultExecuteResultHandler` и stream-handler
extension point. Но prompt automation не является родным сценарием: стандартный `PumpStreamHandler` удобен для
предзаданного stdin, а output-driven send требует своего `ExecuteStreamHandler` со state machine. Документация хороша
для quoting, exit values, watchdog и stream pumping, но не объясняет expect-style ordering, redaction или transcript
semantics. Workaround выглядит тяжелее JDK из-за интерфейсов Exec и асинхронного watchdog state.

### ZeroTurnaround zt-exec

```java
PromptBuffer promptBuffer = new PromptBuffer(16 * 1024, answer);
StartedProcess started = new ProcessExecutor()
        .command(command)
        .redirectOutput(promptBuffer.stdout())
        .redirectError(promptBuffer.stderr())
        .exitValueAny()
        .start();
try (OutputStream stdin = started.getProcess().getOutputStream()) {
    promptBuffer.expect(prompt, timeout);
    promptBuffer.action("send line: <redacted>");
    stdin.write((answer + "\n").getBytes(StandardCharsets.UTF_8));
    stdin.flush();
    promptBuffer.expect(done, timeout);
}
ProcessResult result = started.getFuture().get(timeout.toMillis(), TimeUnit.MILLISECONDS);
return new PromptRun(result.getExitValue(), promptBuffer.transcript());

final class PromptBuffer extends OutputStream {
    private final StringBuilder output = new StringBuilder();
    private final BoundedTranscript transcript;
    private String source = "stdout";

    PromptBuffer(int limit, String secret) {
        transcript = new BoundedTranscript(limit, secret);
    }

    OutputStream stdout() {
        source = "stdout";
        return this;
    }

    OutputStream stderr() {
        return new OutputStream() {
            public void write(int value) {
                transcript.stream("stderr", Character.toString((char) value));
            }
        };
    }

    public synchronized void write(int value) {
        String chunk = transcript.stream(source, Character.toString((char) value));
        output.append(chunk);
        notifyAll();
    }

    synchronized void stream(String source, String chunk) {
        output.append(transcript.stream(source, chunk));
        notifyAll();
    }

    void action(String value) {
        transcript.action(value);
    }

    LineTranscript transcript() {
        return transcript.snapshot();
    }

    synchronized void expect(Pattern pattern, Duration timeout) throws InterruptedException, TimeoutException {
        long deadline = System.nanoTime() + timeout.toNanos();
        transcript.action("expect regex: <redacted>");
        while (!pattern.matcher(output).find()) {
            long millis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
            if (millis <= 0) {
                throw new TimeoutException("expected output not found");
            }
            wait(Math.max(1, millis));
        }
    }
}
```

LOC: 61
Status: workaround
API: 55
Docs: 73
Library: 68
Scenario: 64
Notes: zt-exec приятнее JDK для launch configuration и async start, но prompt automation остается ручным matcher поверх
`redirectOutput` и `Process` handle. README хорошо покрывает one-shot, output redirect, timeout и async `Future`, но
не описывает prompt-driven stdin, transcript boundaries или secret-safe echo. Ошибка timeout приходит через future или
ручной wait loop, поэтому пользователю нужно согласовать lifecycle cleanup с собственным expect helper.

### NuProcess

```java
PromptHandler handler = new PromptHandler(prompt, answer, done);
NuProcessBuilder builder = new NuProcessBuilder(command);
builder.setProcessListener(handler);
NuProcess process = builder.start();
if (!handler.await(timeout)) {
    process.destroy(true);
    throw new TimeoutException("expected output not found");
}
int exit = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
if (process.isRunning()) {
    process.destroy(true);
}
return new PromptRun(exit, handler.transcript());

final class PromptHandler extends NuAbstractProcessHandler {
    private final CountDownLatch complete = new CountDownLatch(1);
    private final StringBuilder output = new StringBuilder();
    private final Pattern prompt;
    private final Pattern done;
    private final BoundedTranscript transcript;
    private final byte[] answerBytes;
    private NuProcess process;
    private boolean answerQueued;
    private boolean answerWritten;

    PromptHandler(Pattern prompt, String answer, Pattern done) {
        this.prompt = prompt;
        this.done = done;
        transcript = new BoundedTranscript(16 * 1024, answer);
        answerBytes = (answer + "\n").getBytes(StandardCharsets.UTF_8);
    }

    public void onStart(NuProcess process) {
        this.process = process;
    }

    public void onStdout(ByteBuffer buffer, boolean closed) {
        String chunk = decode(buffer);
        transcript.stream("stdout", chunk);
        output.append(chunk);
        if (!answerQueued && prompt.matcher(output).find()) {
            transcript.action("send line: <redacted>");
            answerQueued = true;
            process.wantWrite();
        }
        if (done.matcher(output).find()) {
            complete.countDown();
        }
    }

    public void onStderr(ByteBuffer buffer, boolean closed) {
        transcript.stream("stderr", decode(buffer));
    }

    public boolean onStdinReady(ByteBuffer buffer) {
        if (!answerWritten) {
            buffer.put(answerBytes);
            buffer.flip();
            answerWritten = true;
        }
        process.closeStdin(false);
        return false;
    }

    boolean await(Duration timeout) throws InterruptedException {
        return complete.await(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    LineTranscript transcript() {
        return transcript.snapshot();
    }
}
```

LOC: 64
Status: workaround
API: 42
Docs: 60
Library: 70
Scenario: 57
Notes: NuProcess хорошо подходит для большого количества concurrent процессов и non-blocking pipe I/O, но IS05 требует
ручной callback state machine. Пользователь должен сам решить, когда вызывать `wantWrite()`, как писать в
`onStdinReady`, где хранить bounded transcript, как redaction сочетается с decoded output, и как отличить timeout,
EOF и callback failure. Javadoc описывает callbacks, но prompt automation как пользовательский workflow не виден.
Native/JNA слой полезен для scale, но увеличивает portability и maintenance considerations для простого prompt flow.

### Pty4J

```java
Map<String, String> env = new HashMap<>(System.getenv());
env.putIfAbsent("TERM", "xterm");
PtyProcess process = new PtyProcessBuilder()
        .setCommand(command.toArray(String[]::new))
        .setEnvironment(env)
        .start();
PromptBuffer buffer = new PromptBuffer(16 * 1024, answer);
CompletableFuture<Void> reader = CompletableFuture.runAsync(
        () -> pump(process.getInputStream(), "pty", buffer));
try (OutputStream stdin = process.getOutputStream()) {
    buffer.expect(prompt, timeout);
    buffer.action("send line: <redacted>");
    stdin.write((answer + "\n").getBytes(StandardCharsets.UTF_8));
    stdin.flush();
    buffer.expect(done, timeout);
}
boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
if (!exited) {
    process.destroyForcibly();
}
reader.get(1, TimeUnit.SECONDS);
return new PromptRun(process.exitValue(), buffer.transcript());

static void pump(InputStream input, String source, PromptBuffer buffer) {
    try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
        char[] chars = new char[1024];
        int count;
        while ((count = reader.read(chars)) >= 0) {
            buffer.stream(source, new String(chars, 0, count));
        }
    } catch (IOException exception) {
        throw new UncheckedIOException(exception);
    }
}
```

LOC: 33
Status: workaround
API: 45
Docs: 55
Library: 66
Scenario: 55
Notes: Pty4J решает другую часть задачи: дает настоящий PTY для команд, которые иначе не покажут prompt или изменят
buffering. Но matcher, send ordering, result wait, transcript bounds, redaction и failure taxonomy остаются
пользовательским кодом. Для terminal-required prompt это полезный transport, но не expect API. Документация README
быстро показывает `PtyProcessBuilder` и streams, однако edge cases terminal echo, stderr merging, window size и
cleanup нужно проектировать отдельно.

### ExpectIt

```java
Process process = new ProcessBuilder(command).start();
StringBuilder transcript = new StringBuilder();
Appendable redactedInput = new RedactingAppendable(transcript, answer);
try (net.sf.expectit.Expect expect = new ExpectBuilder()
        .withInputs(process.getInputStream(), process.getErrorStream())
        .withOutput(process.getOutputStream())
        .withEchoInput(redactedInput)
        .withTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
        .withExceptionOnFailure()
        .build()) {
    transcript.append("expect regex: <redacted>\n");
    expect.expect(Matchers.regexp(prompt.pattern()));
    transcript.append("send line: <redacted>\n");
    expect.sendLine(answer);
    transcript.append("expect regex: <redacted>\n");
    expect.expect(Matchers.regexp(done.pattern()));
}
boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
if (!exited) {
    process.destroyForcibly();
    process.waitFor(5, TimeUnit.SECONDS);
}
return new PromptRun(process.exitValue(), transcript.toString());
```

LOC: 23
Status: implemented
API: 88
Docs: 82
Library: 75
Scenario: 82
Notes: Это лучший внешний fit для IS05: `ExpectBuilder`, `expect`, `sendLine`, regex/contains matchers, timeout,
input filters и echo hooks прямо совпадают со сценарием. Но process lifecycle остается снаружи через `ProcessBuilder`,
а secret-safe transcript не является готовым contract: `withEchoInput` пишет process output, `withEchoOutput` для
send values использовать нельзя без redaction wrapper, и echoed secrets надо чистить самостоятельно. Документация
содержит quick start, OS process, SSH examples, matchers, filters и thread-safety notes, но проект старый и
maintenance signal слабее, чем у JDK/Apache/JetBrains artifacts.

## Summary

| Candidate | LOC | Status | API | Docs | Library | Scenario |
| --- | ---: | --- | ---: | ---: | ---: | ---: |
| iCLI rewrite | 19 | implemented | 91 | 86 | 84 | 87 |
| JDK ProcessBuilder | 53 | workaround | 52 | 75 | 86 | 70 |
| Apache Commons Exec | 82 | workaround | 44 | 78 | 68 | 60 |
| ZeroTurnaround zt-exec | 61 | workaround | 55 | 73 | 68 | 64 |
| NuProcess | 64 | workaround | 42 | 60 | 70 | 57 |
| Pty4J | 33 | workaround | 45 | 55 | 66 | 55 |
| ExpectIt | 23 | implemented | 88 | 82 | 75 | 82 |

Лучший fit по human-factor: iCLI rewrite, потому что redaction, bounded transcript, match timeout, EOF/timeout
distinction и output ownership являются частью сценарного API, а не пользовательским helper-ом.

Лучший внешний fit: ExpectIt. Оно напрямую реализует expect-style диалог, но не закрывает process lifecycle и
redaction-safe transcript как единый contract.

Самый короткий код: iCLI rewrite среди полноценных secret-safe sketches. ExpectIt почти такой же короткий, если принять
ручной `RedactingAppendable` как небольшой внешний helper.

Самый надежный contract: iCLI rewrite. JDK имеет самую зрелую базовую библиотеку, но не сценарный contract. Commons Exec,
zt-exec, NuProcess и Pty4J пригодны только как transports или process executors: каждый требует ручного expect engine,
и именно там появляются основные human-factor риски.

Публичные источники, использованные для проверки внешних API: [Oracle JDK `ProcessBuilder`](https://docs.oracle.com/en/java/javase/25/docs/api/java.base/java/lang/ProcessBuilder.html),
[Apache Commons Exec Tutorial](https://commons.apache.org/proper/commons-exec/tutorial.html) и
[Javadocs](https://commons.apache.org/proper/commons-exec/apidocs/), [zt-exec README](https://github.com/zeroturnaround/zt-exec),
[NuProcess README](https://github.com/brettwooldridge/NuProcess) и [Javadocs](https://javadoc.io/doc/com.zaxxer/nuprocess/3.0.0),
[Pty4J README](https://github.com/JetBrains/pty4j), [ExpectIt README](https://github.com/agavrilov76/ExpectIt) и
[Javadocs](https://javadoc.io/doc/net.sf.expectit/expectit-core/latest/index.html).
