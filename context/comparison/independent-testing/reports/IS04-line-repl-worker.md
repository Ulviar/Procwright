# IS04: Line-oriented REPL / worker protocol

## Scenario

Проверяем сценарий долгоживущего line-oriented worker: процесс запускается один раз, caller отправляет несколько
request lines (`alpha`, `beta`) через stdin, на каждый request получает ровно один logical response line из stdout,
ответы не смешиваются между concurrent/последовательными запросами, malformed response и слишком длинная stdout line
завершаются bounded failure behavior: request получает ошибку/timeout, worker закрывается, старое protocol state не
используется дальше.

Для sketches ниже считаю protocol успешным, если response имеет форму `ok:<payload>`, и считаю line слишком длинной
после 4096 символов до line separator.

В manual candidates повторяются небольшие local helpers (`LineEvent`, `stripCr`, `BoundedLineOutput`). Чтобы не
дублировать одинаковый код в каждом разделе, они показаны в первых sketches и переиспользуются дальше; LOC учитывает
helpers там, где candidate не предоставляет такую семантику сам.

## Implementations

### iCLI rewrite

```java
ResponseDecoder okLine = reader -> {
    String line = reader.readLine();
    if (!line.startsWith("ok:")) {
        throw new IllegalArgumentException("malformed response: " + line);
    }
    return List.of(line.substring(3));
};

LineSessionOptions lineOptions = LineSessionOptions.defaults()
        .withRequestTimeout(Duration.ofSeconds(2))
        .withMaxLineChars(4096)
        .withResponseDecoder(okLine);

CommandService worker = new CommandService(
        CommandSpec.of("worker"),
        RunOptions.defaults(),
        SessionOptions.defaults().withShutdown(
                ShutdownPolicy.interruptThenKill(Duration.ofMillis(200), Duration.ofSeconds(1))),
        lineOptions);

try (LineSession session = worker.lineSession(call -> call.args("--line-worker"))) {
    String first = session.request("alpha").text();
    String second = session.request("beta").text();
}
```

LOC: 20
Status: implemented
API: 94
Docs: 86
Library: 88
Scenario: 90
Notes: Сценарий выражается напрямую через `lineSession`, `LineSessionOptions`, `ResponseDecoder` и typed
`LineSessionException`. Requests сериализуются внутри `LineSession`, stdout/stderr дренируются библиотекой, timeout и
decoder failure закрывают session, `maxLineChars` ограничивает незавершенную stdout line. Документация в README,
cookbook, contracts и Javadocs явно описывает line session, но проект еще `0.0.0-SNAPSHOT`, поэтому maintenance/release
story слабее зрелой опубликованной библиотеки.

### JDK ProcessBuilder

```java
final class JdkLineWorker implements AutoCloseable {
    private final Process process;
    private final BufferedWriter stdin;
    private final BlockingQueue<LineEvent> lines = new ArrayBlockingQueue<>(32);
    private final ReentrantLock oneAtATime = new ReentrantLock();

    JdkLineWorker(List<String> command) throws IOException {
        process = new ProcessBuilder(command).start();
        stdin = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
        Thread.ofVirtual().start(() -> pump(process.getInputStream()));
        Thread.ofVirtual().start(() -> drain(process.getErrorStream()));
    }

    String request(String value, Duration timeout) throws Exception {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("bad request line");
        }
        oneAtATime.lock();
        try {
            stdin.write(value);
            stdin.newLine();
            stdin.flush();
            LineEvent event = lines.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (event == null) {
                throw fail("timeout");
            }
            if (event.failure() != null) {
                throw fail(event.failure().getMessage());
            }
            if (!event.line().startsWith("ok:")) {
                throw fail("malformed response: " + event.line());
            }
            return event.line().substring(3);
        } catch (Exception failure) {
            close();
            throw failure;
        } finally {
            oneAtATime.unlock();
        }
    }

    private void pump(InputStream input) {
        try (Reader reader = new InputStreamReader(input, StandardCharsets.UTF_8)) {
            StringBuilder line = new StringBuilder();
            for (int ch; (ch = reader.read()) >= 0; ) {
                if (ch == '\n') {
                    lines.put(LineEvent.line(stripCr(line)));
                    line.setLength(0);
                } else if (line.length() >= 4096) {
                    throw new IOException("line too long");
                } else {
                    line.append((char) ch);
                }
            }
            lines.offer(LineEvent.failure(new EOFException("stdout closed")));
        } catch (Exception failure) {
            lines.offer(LineEvent.failure(failure));
        }
    }

    private static String stripCr(StringBuilder line) {
        int length = line.length();
        return length > 0 && line.charAt(length - 1) == '\r'
                ? line.substring(0, length - 1)
                : line.toString();
    }

    private static void drain(InputStream stream) {
        try (stream) {
            stream.transferTo(OutputStream.nullOutputStream());
        } catch (IOException ignored) {
        }
    }

    private IllegalStateException fail(String message) {
        return new IllegalStateException(message);
    }

    @Override
    public void close() {
        process.destroyForcibly();
    }
}

record LineEvent(String line, Exception failure) {
    static LineEvent line(String line) {
        return new LineEvent(line, null);
    }

    static LineEvent failure(Exception failure) {
        return new LineEvent(null, failure);
    }
}

try (JdkLineWorker worker = new JdkLineWorker(List.of("worker", "--line-worker"))) {
    worker.request("alpha", Duration.ofSeconds(2));
    worker.request("beta", Duration.ofSeconds(2));
}
```

LOC: 77
Status: implemented
API: 55
Docs: 74
Library: 68
Scenario: 64
Notes: JDK дает переносимую базу (`ProcessBuilder`, streams, `destroyForcibly`), но все инварианты scenario принадлежат
пользовательскому harness: request lock, stdout line pump, stderr drain, max-line accounting, timeout, close-on-failure,
typed failure reasons. Документация API хорошая, но она документирует primitive process pipes, а не REPL/worker
protocol.

### Apache Commons Exec

```java
final class CommonsExecLineWorker implements AutoCloseable {
    private final PipedOutputStream stdin = new PipedOutputStream();
    private final BlockingQueue<LineEvent> lines = new ArrayBlockingQueue<>(32);
    private final ReentrantLock oneAtATime = new ReentrantLock();
    private final ExecuteWatchdog watchdog =
            ExecuteWatchdog.builder().setTimeout(ExecuteWatchdog.INFINITE_TIMEOUT_DURATION).get();

    CommonsExecLineWorker(CommandLine command) throws IOException {
        PipedInputStream childInput = new PipedInputStream(stdin);
        PumpStreamHandler streams = new PumpStreamHandler(
                new BoundedLineOutput(lines, this::close),
                OutputStream.nullOutputStream(),
                childInput);
        DefaultExecutor executor = DefaultExecutor.builder().setExecuteStreamHandler(streams).get();
        executor.setExitValues(null);
        executor.setWatchdog(watchdog);
        executor.execute(command, new DefaultExecuteResultHandler());
    }

    String request(String value, Duration timeout) throws Exception {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("bad request line");
        }
        oneAtATime.lock();
        try {
            stdin.write((value + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            LineEvent event = lines.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (event == null || event.failure() != null || !event.line().startsWith("ok:")) {
                throw new IllegalStateException("bad line-session response");
            }
            return event.line().substring(3);
        } catch (Exception failure) {
            close();
            throw failure;
        } finally {
            oneAtATime.unlock();
        }
    }

    @Override
    public void close() {
        watchdog.destroyProcess();
    }
}

final class BoundedLineOutput extends OutputStream {
    private final BlockingQueue<LineEvent> lines;
    private final Runnable close;
    private final ByteArrayOutputStream line = new ByteArrayOutputStream();

    BoundedLineOutput(BlockingQueue<LineEvent> lines, Runnable close) {
        this.lines = lines;
        this.close = close;
    }

    @Override
    public synchronized void write(int b) throws IOException {
        if (b == '\n') {
            lines.offer(LineEvent.line(line.toString(StandardCharsets.UTF_8)));
            line.reset();
            return;
        }
        if (b != '\r') {
            line.write(b);
        }
        if (line.size() > 4096) {
            close.run();
            throw new IOException("line too long");
        }
    }
}

CommandLine command = new CommandLine("worker").addArgument("--line-worker", false);
try (CommonsExecLineWorker worker = new CommonsExecLineWorker(command)) {
    worker.request("alpha", Duration.ofSeconds(2));
    worker.request("beta", Duration.ofSeconds(2));
}
```

LOC: 77
Status: workaround
API: 45
Docs: 68
Library: 61
Scenario: 56
Notes: Commons Exec умеет executor, stream handler и watchdog, но interactive request/response получается через
`PipedInputStream`/`PipedOutputStream`, async `DefaultExecuteResultHandler` и собственный bounded line parser. `LogOutputStream`
удобен для completed lines, но не владеет cap для незавершенной слишком длинной line, поэтому нужен custom
`OutputStream`. Watchdog помогает убить процесс, но per-request protocol state и failure semantics остаются у caller.

### ZeroTurnaround zt-exec

```java
final class ZtExecLineWorker implements AutoCloseable {
    private final PipedOutputStream stdin = new PipedOutputStream();
    private final BlockingQueue<LineEvent> lines = new ArrayBlockingQueue<>(32);
    private final ReentrantLock oneAtATime = new ReentrantLock();
    private final StartedProcess started;

    ZtExecLineWorker(List<String> command) throws IOException {
        PipedInputStream childInput = new PipedInputStream(stdin);
        started = new ProcessExecutor()
                .command(command)
                .redirectInput(childInput)
                .redirectOutput(new BoundedLineOutput(lines, this::close))
                .redirectError(OutputStream.nullOutputStream())
                .exitValueAny()
                .start();
    }

    String request(String value, Duration timeout) throws Exception {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("bad request line");
        }
        oneAtATime.lock();
        try {
            stdin.write((value + "\n").getBytes(StandardCharsets.UTF_8));
            stdin.flush();
            LineEvent event = lines.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (event == null || event.failure() != null || !event.line().startsWith("ok:")) {
                throw new IllegalStateException("bad line-session response");
            }
            return event.line().substring(3);
        } catch (Exception failure) {
            close();
            throw failure;
        } finally {
            oneAtATime.unlock();
        }
    }

    @Override
    public void close() {
        started.getProcess().destroyForcibly();
    }
}

try (ZtExecLineWorker worker = new ZtExecLineWorker(List.of("worker", "--line-worker"))) {
    worker.request("alpha", Duration.ofSeconds(2));
    worker.request("beta", Duration.ofSeconds(2));
}
```

LOC: 75
Status: workaround
API: 50
Docs: 62
Library: 60
Scenario: 56
Notes: `ProcessExecutor` делает запуск и redirect ergonomics короче, чем raw JDK, но line worker все равно требует
внешний request lock, pipe-backed stdin и custom bounded line output (`BoundedLineOutput` из Commons Exec sketch).
`timeout(...)` полезен для one-shot execution, но для долгоживущего worker нужен ручной per-request timeout и
destroy-on-protocol-failure.

### NuProcess

```java
final class NuLineWorker extends NuAbstractProcessHandler implements AutoCloseable {
    private final BlockingQueue<ByteBuffer> writes = new LinkedBlockingQueue<>();
    private final BlockingQueue<LineEvent> lines = new ArrayBlockingQueue<>(32);
    private final ReentrantLock oneAtATime = new ReentrantLock();
    private final StringBuilder current = new StringBuilder();
    private volatile NuProcess process;

    NuLineWorker(List<String> command) {
        NuProcessBuilder builder = new NuProcessBuilder(command);
        builder.setProcessListener(this);
        process = builder.start();
    }

    String request(String value, Duration timeout) throws Exception {
        if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
            throw new IllegalArgumentException("bad request line");
        }
        oneAtATime.lock();
        try {
            writes.add(StandardCharsets.UTF_8.encode(value + "\n"));
            process.wantWrite();
            LineEvent event = lines.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (event == null || event.failure() != null || !event.line().startsWith("ok:")) {
                throw new IllegalStateException("bad line-session response");
            }
            return event.line().substring(3);
        } catch (Exception failure) {
            close();
            throw failure;
        } finally {
            oneAtATime.unlock();
        }
    }

    @Override
    public void onStart(NuProcess process) {
        this.process = process;
    }

    @Override
    public boolean onStdinReady(ByteBuffer buffer) {
        ByteBuffer next = writes.peek();
        if (next == null) {
            return false;
        }
        while (next.hasRemaining() && buffer.hasRemaining()) {
            buffer.put(next.get());
        }
        if (!next.hasRemaining()) {
            writes.remove();
        }
        buffer.flip();
        return !writes.isEmpty();
    }

    @Override
    public void onStdout(ByteBuffer buffer, boolean closed) {
        while (buffer.hasRemaining()) {
            int ch = buffer.get() & 0xff;
            if (ch == '\n') {
                lines.offer(LineEvent.line(stripCr(current)));
                current.setLength(0);
            } else if (current.length() >= 4096) {
                lines.offer(LineEvent.failure(new IOException("line too long")));
                close();
            } else {
                current.append((char) ch);
            }
        }
        if (closed) {
            lines.offer(LineEvent.failure(new EOFException("stdout closed")));
        }
    }

    @Override
    public void onStderr(ByteBuffer buffer, boolean closed) {
        buffer.position(buffer.limit());
    }

    @Override
    public void onExit(int statusCode) {
        lines.offer(LineEvent.failure(new EOFException("exit " + statusCode)));
    }

    @Override
    public void close() {
        NuProcess currentProcess = process;
        if (currentProcess != null) {
            currentProcess.destroy(true);
        }
    }
}

try (NuLineWorker worker = new NuLineWorker(List.of("worker", "--line-worker"))) {
    worker.request("alpha", Duration.ofSeconds(2));
    worker.request("beta", Duration.ofSeconds(2));
}
```

LOC: 90
Status: implemented
API: 70
Docs: 70
Library: 78
Scenario: 73
Notes: NuProcess лучше внешних one-shot библиотек подходит для долгоживущего worker: stdout/stderr/stdin являются
callback-driven, нет необходимости блокировать OS threads на stream reads. Цена — собственная state machine: line
framing, request serialization, write queue, decode errors, EOF, timeout и shutdown semantics; snippet использует те же
`LineEvent`/`stripCr` helpers, что JDK sketch. Для high-throughput worker это самый сильный внешний primitive, но
human-factor хуже специализированного сценарного API.

### Pty4J

```java
Map<String, String> env = new HashMap<>(System.getenv());
env.putIfAbsent("TERM", "dumb");

PtyProcess process = new PtyProcessBuilder()
        .setCommand(new String[] {"worker", "--line-worker"})
        .setEnvironment(env)
        .setRedirectErrorStream(true)
        .setInitialColumns(120)
        .setInitialRows(40)
        .start();

try (JdkLineWorker worker = JdkLineWorker.overProcess(
        process,
        line -> line.replace("\r", ""),
        line -> !line.equals("alpha") && !line.equals("beta"))) {
    worker.request("alpha", Duration.ofSeconds(2));
    worker.request("beta", Duration.ofSeconds(2));
}
```

LOC: 91, если считать тот же manual line harness, что в JDK sketch; 14 строк — только PTY-specific wiring.
Status: workaround
API: 38
Docs: 55
Library: 53
Scenario: 47
Notes: Pty4J решает другой сценарий: terminal capability. Для JSONL/compiler daemon style worker PTY может добавить echo,
CR/LF normalization, terminal buffering, ANSI/control sequences и stream merging. Реализация возможна только как
workaround поверх того же ручного line harness, что и `ProcessBuilder`, плюс фильтрация echo/terminal artifacts. Это
имеет смысл, только если сам worker требует TTY.

### ExpectIt

```java
Process process = new ProcessBuilder("worker", "--line-worker").start();
try (Expect expect = new ExpectBuilder()
        .withInputs(process.getInputStream())
        .withOutput(process.getOutputStream())
        .withTimeout(2, TimeUnit.SECONDS)
        .withBufferSize(4096)
        .withExceptionOnFailure()
        .build()) {
    String first = request(expect, "alpha");
    String second = request(expect, "beta");
} catch (Exception failure) {
    process.destroyForcibly();
    throw failure;
}

static String request(Expect expect, String value) throws IOException {
    if (value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0) {
        throw new IllegalArgumentException("bad request line");
    }
    expect.sendLine(value);
    Result result = expect.expect(Matchers.regexp("(?m)^ok:([^\\r\\n]{1,4096})\\r?\\n"));
    return result.group(1);
}
```

LOC: 23
Status: workaround
API: 62
Docs: 60
Library: 55
Scenario: 59
Notes: Для happy path ExpectIt выглядит коротко: `sendLine` + `expect(regexp(...))` хорошо выражает prompt-like обмен.
Но это matcher buffer API, а не line-session API. Request/response ownership, stderr handling, close-on-failure и
process lifecycle остаются снаружи. `withBufferSize(4096)` не равен строгому protocol cap для незавершенной слишком
длинной строки: unmatched long output будет ждать timeout и может удерживаться в expect buffer. Поэтому это хороший
prompt workaround, но слабый contract для worker protocol.

## Summary

Лучший fit для IS04 — iCLI rewrite: он единственный из кандидатов выражает line worker как first-class scenario,
сериализует requests, дает response decoder, bounded transcript, `maxLineChars`, timeout/EOF/failure distinction и
закрывает session при protocol uncertainty.

Самый короткий выглядящий код — ExpectIt, но он не покрывает строгий too-long-line contract. Самый короткий полноценный
контракт — iCLI. Среди внешних библиотек самый надежный primitive — NuProcess: event-driven I/O хорошо ложится на
долгоживущий worker, но требует написать собственный protocol runtime. JDK `ProcessBuilder` остается хорошим baseline,
если команда готова владеть всем harness самостоятельно.

Commons Exec и zt-exec полезны для one-shot execution и stream pumping, но line-oriented worker приходится строить через
pipes, async launch и custom bounded output parser. Pty4J не является подходящим default для IS04: его нужно выбирать
только для worker, которому действительно нужен terminal, иначе PTY behavior добавляет лишние риски смешивания и
искажения protocol lines.
