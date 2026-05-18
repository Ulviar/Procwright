# IS07: Warm worker pool

## Scenario

Реальный кейс: повторные formatter/compiler requests или дорогой CLI startup, где несколько line-oriented worker
processes можно безопасно переиспользовать между независимыми requests.

Сценарий проверяет:

- worker reuse: два запроса могут попасть в один уже поднятый worker;
- bounded acquire: при занятых workers caller получает ограниченное ожидание, а не бесконечный block;
- health/reset или cleanup behavior: worker проверяется перед reuse, очищается после request или retire/close при
  ошибке;
- caller видит сценарный contract, а не низкоуровневый набор stream/process primitives.

Для кандидатов без собственного pool API скетчи используют пользовательский `BoundedWorkerPool`. Его LOC засчитан в
каждый workaround, потому что это не test harness, а основная логика сценария, которую библиотека не предоставляет.

```java
interface ThrowingFunction<W, T> {
    T apply(W worker) throws Exception;
}

interface ThrowingPredicate<W> {
    boolean test(W worker) throws Exception;
}

interface ThrowingConsumer<W> {
    void accept(W worker) throws Exception;
}

final class BoundedWorkerPool<W extends AutoCloseable> implements AutoCloseable {
    private final BlockingQueue<W> idle = new LinkedBlockingQueue<>();
    private final Semaphore capacity;
    private final Duration acquireTimeout;
    private final Callable<W> opener;
    private final ThrowingPredicate<W> health;
    private final ThrowingConsumer<W> reset;

    BoundedWorkerPool(int maxSize, int warmupSize, Duration acquireTimeout, Callable<W> opener,
            ThrowingPredicate<W> health, ThrowingConsumer<W> reset) throws Exception {
        this.capacity = new Semaphore(maxSize);
        this.acquireTimeout = acquireTimeout;
        this.opener = opener;
        this.health = health;
        this.reset = reset;
        for (int i = 0; i < warmupSize; i++) {
            capacity.acquire();
            idle.add(opener.call());
        }
    }

    <T> T use(ThrowingFunction<W, T> request) throws Exception {
        W worker = acquire();
        boolean reusable = false;
        try {
            T result = request.apply(worker);
            reset.accept(worker);
            reusable = health.test(worker);
            return result;
        } finally {
            release(worker, reusable);
        }
    }

    private W acquire() throws Exception {
        W worker = idle.poll();
        if (worker != null && health.test(worker)) {
            return worker;
        }
        retire(worker);
        if (!capacity.tryAcquire(acquireTimeout.toMillis(), TimeUnit.MILLISECONDS)) {
            throw new TimeoutException("worker acquire timed out");
        }
        try {
            return opener.call();
        } catch (Exception failure) {
            capacity.release();
            throw failure;
        }
    }

    private void release(W worker, boolean reusable) {
        if (reusable) {
            idle.offer(worker);
        } else {
            retire(worker);
        }
    }

    private void retire(W worker) {
        if (worker == null) {
            return;
        }
        try {
            worker.close();
        } catch (Exception ignored) {
        } finally {
            capacity.release();
        }
    }

    public void close() {
        idle.forEach(this::retire);
    }
}
```

LOC shared workaround pool: 78.

LOC для workaround-кандидатов ниже являются консервативной нижней границей: расчет включает общий pool и candidate worker sketch,
но не imports, fixture program и раскрытие небольших утилит вроде `readLineWithTimeout`, `awaitOrKill` и `LineOutput`.

## Implementations

### iCLI rewrite

```java
CommandService service = CommandService.forCommand("formatterd");

try (PooledLineSession pool = service.pooled(call -> call
        .args("--line-protocol")
        .maxSize(4)
        .warmupSize(2)
        .acquireTimeout(Duration.ofMillis(200))
        .maxRequestsPerWorker(500)
        .maxWorkerAge(Duration.ofMinutes(10))
        .healthCheck(worker -> "ok".equals(worker.request("health", Duration.ofMillis(100)).text()))
        .reset(worker -> worker.request("reset", Duration.ofMillis(100))))) {
    String firstPid = pool.request("pid", Duration.ofSeconds(1)).text();
    pool.request("format src/A.java", Duration.ofSeconds(2));
    String secondPid = pool.request("pid", Duration.ofSeconds(1)).text();
    assert firstPid.equals(secondPid);
    PooledLineSessionMetrics metrics = pool.metrics();
    assert metrics.created() <= 4;
}
```

LOC: 17
Status: implemented
API: 94
Docs: 86
Library: 85
Scenario: 89
Notes: Сценарий выражается напрямую через `CommandService.pooled(...)`: `maxSize`, `warmupSize`,
`acquireTimeout`, `healthCheck`, `reset`, retirement by request count/age, `metrics()`, `close()` и
`awaitDrained(...)` являются частью публичного contract. Хорошо отделены acquire timeout и per-request timeout. Главные
ограничения: pool только для `LineSession`, health/reset hooks остаются ответственностью пользователя, библиотека еще
`0.0.0-SNAPSHOT`.

### JDK ProcessBuilder

```java
final class JdkWorker implements AutoCloseable {
    private final Process process;
    private final BufferedReader stdout;
    private final BufferedWriter stdin;

    static JdkWorker start(List<String> command) throws IOException {
        return new JdkWorker(new ProcessBuilder(command).start());
    }

    private JdkWorker(Process process) {
        this.process = process;
        this.stdout = process.inputReader(StandardCharsets.UTF_8);
        this.stdin = process.outputWriter(StandardCharsets.UTF_8);
    }

    synchronized String request(String line, Duration timeout) throws Exception {
        stdin.write(line);
        stdin.newLine();
        stdin.flush();
        return readLineWithTimeout(stdout, timeout);
    }

    boolean healthy(Duration timeout) {
        try {
            return process.isAlive() && request("health", timeout).equals("ok");
        } catch (Exception failure) {
            return false;
        }
    }

    public void close() {
        process.destroy();
        awaitOrKill(process, Duration.ofMillis(200));
    }
}

try (BoundedWorkerPool<JdkWorker> pool = new BoundedWorkerPool<>(
        2,
        1,
        Duration.ofMillis(200),
        () -> JdkWorker.start(command),
        worker -> worker.healthy(Duration.ofMillis(100)),
        worker -> worker.request("reset", Duration.ofMillis(100)))) {
    String pid1 = pool.use(worker -> worker.request("pid", Duration.ofSeconds(1)));
    pool.use(worker -> worker.request("format src/A.java", Duration.ofSeconds(2)));
    String pid2 = pool.use(worker -> worker.request("pid", Duration.ofSeconds(1)));
    assert pid1.equals(pid2);
}
```

LOC: 120
Status: workaround
API: 45
Docs: 72
Library: 78
Scenario: 64
Notes: JDK дает зрелый `Process`, streams, `isAlive()`, `destroy()` и `destroyForcibly()`, но сам сценарий полностью
принадлежит пользователю: acquire queue, request serialization, stdout line framing, stderr draining, per-request
timeout, reset/health policy и cleanup нужно проектировать отдельно. Failure semantics легко сделать inconsistent:
например, timeout чтения строки должен закрывать worker и освобождать capacity.

### Apache Commons Exec

```java
final class CommonsWorker implements AutoCloseable {
    private final ExecuteWatchdog watchdog = ExecuteWatchdog.builder()
            .setTimeout(ExecuteWatchdog.INFINITE_TIMEOUT_DURATION)
            .get();
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final PipedOutputStream stdin;
    private final CompletableFuture<Integer> exit = new CompletableFuture<>();

    static CommonsWorker start(CommandLine command, Map<String, String> environment) throws Exception {
        PipedInputStream childInput = new PipedInputStream();
        PipedOutputStream stdin = new PipedOutputStream(childInput);
        CommonsWorker worker = new CommonsWorker(stdin);
        PumpStreamHandler streams = new PumpStreamHandler(new LineOutput(worker.lines), OutputStream.nullOutputStream(), childInput);
        DefaultExecutor executor = DefaultExecutor.builder().setExecuteStreamHandler(streams).get();
        executor.setExitValues(null);
        executor.setWatchdog(worker.watchdog);
        executor.execute(command, environment, new ExecuteResultHandler() {
            public void onProcessComplete(int exitValue) {
                worker.exit.complete(exitValue);
            }

            public void onProcessFailed(ExecuteException failure) {
                worker.exit.completeExceptionally(failure);
            }
        });
        return worker;
    }

    private CommonsWorker(PipedOutputStream stdin) {
        this.stdin = stdin;
    }

    synchronized String request(String line, Duration timeout) throws Exception {
        stdin.write((line + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
        String response = lines.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (response == null) {
            close();
            throw new TimeoutException("worker response timed out");
        }
        return response;
    }

    boolean healthy(Duration timeout) {
        try {
            return !exit.isDone() && request("health", timeout).equals("ok");
        } catch (Exception failure) {
            return false;
        }
    }

    public void close() {
        watchdog.destroyProcess();
    }
}

try (BoundedWorkerPool<CommonsWorker> pool = new BoundedWorkerPool<>(
        2, 1, Duration.ofMillis(200),
        () -> CommonsWorker.start(commandLine, environment),
        worker -> worker.healthy(Duration.ofMillis(100)),
        worker -> worker.request("reset", Duration.ofMillis(100)))) {
    String pid1 = pool.use(worker -> worker.request("pid", Duration.ofSeconds(1)));
    pool.use(worker -> worker.request("format src/A.java", Duration.ofSeconds(2)));
    String pid2 = pool.use(worker -> worker.request("pid", Duration.ofSeconds(1)));
    assert pid1.equals(pid2);
}
```

LOC: 136
Status: workaround
API: 37
Docs: 62
Library: 68
Scenario: 54
Notes: Commons Exec хорошо покрывает one-shot execution, watchdog и stream pumping, но long-lived bidirectional worker
получается неестественно: нужен `PipedInputStream`, custom `OutputStream` для line parsing, async
`ExecuteResultHandler` и ручное управление `ExecuteWatchdog.destroyProcess()`. Process handle скрыт за executor
моделью, поэтому health/reset и cleanup выглядят как harness поверх библиотеки, а не как библиотечный сценарий.

### ZeroTurnaround zt-exec

```java
final class ZtWorker implements AutoCloseable {
    private final Process process;
    private final Future<ProcessResult> result;
    private final BufferedReader stdout;
    private final BufferedWriter stdin;

    static ZtWorker start(List<String> command) throws IOException {
        StartedProcess started = new ProcessExecutor()
                .command(command)
                .exitValueAny()
                .start();
        return new ZtWorker(started.process(), started.future());
    }

    private ZtWorker(Process process, Future<ProcessResult> result) {
        this.process = process;
        this.result = result;
        this.stdout = process.inputReader(StandardCharsets.UTF_8);
        this.stdin = process.outputWriter(StandardCharsets.UTF_8);
    }

    synchronized String request(String line, Duration timeout) throws Exception {
        stdin.write(line);
        stdin.newLine();
        stdin.flush();
        return readLineWithTimeout(stdout, timeout);
    }

    boolean healthy(Duration timeout) {
        try {
            return process.isAlive() && request("health", timeout).equals("ok");
        } catch (Exception failure) {
            return false;
        }
    }

    public void close() {
        process.destroy();
        result.cancel(true);
        awaitOrKill(process, Duration.ofMillis(200));
    }
}

try (BoundedWorkerPool<ZtWorker> pool = new BoundedWorkerPool<>(
        2, 1, Duration.ofMillis(200),
        () -> ZtWorker.start(command),
        worker -> worker.healthy(Duration.ofMillis(100)),
        worker -> worker.request("reset", Duration.ofMillis(100)))) {
    String pid1 = pool.use(worker -> worker.request("pid", Duration.ofSeconds(1)));
    pool.use(worker -> worker.request("format src/A.java", Duration.ofSeconds(2)));
    String pid2 = pool.use(worker -> worker.request("pid", Duration.ofSeconds(1)));
    assert pid1.equals(pid2);
}
```

LOC: 126
Status: workaround
API: 43
Docs: 64
Library: 66
Scenario: 56
Notes: zt-exec улучшает запуск и one-shot ergonomics (`ProcessExecutor`, timeout, exit handling), но после
`start()` сценарий почти возвращается к raw `Process`: line protocol, bounded acquire, reset/health и pool lifecycle
пишутся вручную. Для warm pool fluent API помогает меньше, чем в one-shot сценариях, потому что главная сложность не в
старте процесса, а в корректном lease/reuse/retire contract.

### NuProcess

```java
final class NuWorker extends NuAbstractProcessHandler implements AutoCloseable {
    private final BlockingQueue<String> lines = new LinkedBlockingQueue<>();
    private final BlockingQueue<byte[]> writes = new LinkedBlockingQueue<>();
    private final StringBuilder currentLine = new StringBuilder();
    private volatile NuProcess process;

    static NuWorker start(List<String> command) {
        NuWorker worker = new NuWorker();
        NuProcessBuilder builder = new NuProcessBuilder(command);
        builder.setProcessListener(worker);
        worker.process = builder.start();
        return worker;
    }

    synchronized String request(String line, Duration timeout) throws Exception {
        writes.add((line + "\n").getBytes(StandardCharsets.UTF_8));
        process.wantWrite();
        String response = lines.poll(timeout.toMillis(), TimeUnit.MILLISECONDS);
        if (response == null) {
            close();
            throw new TimeoutException("worker response timed out");
        }
        return response;
    }

    public boolean onStdinReady(ByteBuffer buffer) {
        byte[] next = writes.poll();
        if (next == null) {
            return false;
        }
        buffer.put(next);
        buffer.flip();
        return !writes.isEmpty();
    }

    public void onStdout(ByteBuffer buffer, boolean closed) {
        while (buffer.hasRemaining()) {
            char ch = (char) buffer.get();
            if (ch == '\n') {
                lines.offer(currentLine.toString());
                currentLine.setLength(0);
            } else {
                currentLine.append(ch);
            }
        }
    }

    boolean healthy(Duration timeout) {
        try {
            return process.isRunning() && request("health", timeout).equals("ok");
        } catch (Exception failure) {
            return false;
        }
    }

    public void close() {
        process.destroy(true);
    }
}

try (BoundedWorkerPool<NuWorker> pool = new BoundedWorkerPool<>(
        2, 1, Duration.ofMillis(200),
        () -> NuWorker.start(command),
        worker -> worker.healthy(Duration.ofMillis(100)),
        worker -> worker.request("reset", Duration.ofMillis(100)))) {
    String pid1 = pool.use(worker -> worker.request("pid", Duration.ofSeconds(1)));
    pool.use(worker -> worker.request("format src/A.java", Duration.ofSeconds(2)));
    String pid2 = pool.use(worker -> worker.request("pid", Duration.ofSeconds(1)));
    assert pid1.equals(pid2);
}
```

LOC: 136
Status: workaround
API: 36
Docs: 57
Library: 72
Scenario: 55
Notes: NuProcess подходит для non-blocking process I/O и может быть эффективен под высокой конкуррентностью, но
human-factor цена для pool высокая: пользователь пишет callback state machine, byte-to-line decoder, очередь stdin,
timeout semantics, worker health и общий pool. Библиотека дает хорошие низкоуровневые hooks, но не владеет
line-session или worker-pool invariants.

### Pty4J

```java
final class PtyWorker implements AutoCloseable {
    private final PtyProcess process;
    private final BufferedReader stdout;
    private final BufferedWriter stdin;

    static PtyWorker start(String[] command, Map<String, String> environment) throws IOException {
        PtyProcess process = new PtyProcessBuilder()
                .setCommand(command)
                .setEnvironment(environment)
                .setInitialColumns(120)
                .setInitialRows(40)
                .start();
        return new PtyWorker(process);
    }

    private PtyWorker(PtyProcess process) {
        this.process = process;
        this.stdout = process.inputReader(StandardCharsets.UTF_8);
        this.stdin = process.outputWriter(StandardCharsets.UTF_8);
    }

    synchronized String request(String line, Duration timeout) throws Exception {
        stdin.write(line);
        stdin.newLine();
        stdin.flush();
        return readLineWithTimeout(stdout, timeout);
    }

    boolean healthy(Duration timeout) {
        try {
            return process.isRunning() && request("health", timeout).equals("ok");
        } catch (Exception failure) {
            return false;
        }
    }

    public void close() {
        process.destroyForcibly();
    }
}

try (BoundedWorkerPool<PtyWorker> pool = new BoundedWorkerPool<>(
        2, 1, Duration.ofMillis(200),
        () -> PtyWorker.start(command, environment),
        worker -> worker.healthy(Duration.ofMillis(100)),
        worker -> worker.request("reset", Duration.ofMillis(100)))) {
    String pid1 = pool.use(worker -> worker.request("pid", Duration.ofSeconds(1)));
    pool.use(worker -> worker.request("format src/A.java", Duration.ofSeconds(2)));
    String pid2 = pool.use(worker -> worker.request("pid", Duration.ofSeconds(1)));
    assert pid1.equals(pid2);
}
```

LOC: 126
Status: workaround
API: 28
Docs: 50
Library: 61
Scenario: 46
Notes: Pty4J решает другой слой: terminal transport. Если worker действительно требует PTY, библиотека полезна для
запуска такого process, но warm pool, bounded acquire, health/reset и request framing остаются полностью внешним кодом.
Для обычного formatter/compiler daemon PTY еще и добавляет лишнюю terminal state/portability surface.

### ExpectIt

```java
final class ExpectItWorker implements AutoCloseable {
    private final Process process;
    private final Expect expect;

    static ExpectItWorker start(List<String> command) throws Exception {
        Process process = new ProcessBuilder(command).start();
        Expect expect = new ExpectBuilder()
                .withInputs(process.getInputStream())
                .withOutput(process.getOutputStream())
                .withTimeout(200, TimeUnit.MILLISECONDS)
                .build();
        return new ExpectItWorker(process, expect);
    }

    private ExpectItWorker(Process process, Expect expect) {
        this.process = process;
        this.expect = expect;
    }

    synchronized String request(String line, Duration timeout) throws Exception {
        expect.withTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
        expect.sendLine(line);
        return expect.expect(Matchers.contains("response:" + line)).getInput();
    }

    boolean healthy(Duration timeout) {
        try {
            return process.isAlive() && request("health", timeout).contains("response:health");
        } catch (Exception failure) {
            return false;
        }
    }

    public void close() throws IOException {
        expect.close();
        process.destroy();
        awaitOrKill(process, Duration.ofMillis(200));
    }
}

try (BoundedWorkerPool<ExpectItWorker> pool = new BoundedWorkerPool<>(
        2, 1, Duration.ofMillis(200),
        () -> ExpectItWorker.start(command),
        worker -> worker.healthy(Duration.ofMillis(100)),
        worker -> worker.request("reset", Duration.ofMillis(100)))) {
    String pid1 = pool.use(worker -> worker.request("pid", Duration.ofSeconds(1)));
    pool.use(worker -> worker.request("format src/A.java", Duration.ofSeconds(2)));
    String pid2 = pool.use(worker -> worker.request("pid", Duration.ofSeconds(1)));
    assert pid1.equals(pid2);
}
```

LOC: 120
Status: workaround
API: 34
Docs: 55
Library: 58
Scenario: 48
Notes: ExpectIt хорош для prompt matching поверх уже открытых streams, но не является process lifecycle или pool
library. Для line worker pool приходится дополнительно использовать `ProcessBuilder` или другой launcher, писать
bounded acquire и lifecycle, а затем следить, чтобы expect buffer/matcher не съедал ответы соседних requests. Reset и
health можно выразить expect-командами, но contract остается пользовательским.

## Summary

| Candidate | LOC | Status | API | Docs | Library | Scenario | Fit |
| --- | ---: | --- | ---: | ---: | ---: | ---: | --- |
| iCLI rewrite | 17 | implemented | 94 | 86 | 85 | 89 | Лучший прямой fit: scenario-first API владеет pool invariants. |
| JDK ProcessBuilder | 120 | workaround | 45 | 72 | 78 | 64 | Самая надежная низкоуровневая база для custom pool, но весь contract ручной. |
| Apache Commons Exec | 136 | workaround | 37 | 62 | 68 | 54 | Сильнее в one-shot/watchdog, слабый fit для bidirectional warm workers. |
| ZeroTurnaround zt-exec | 126 | workaround | 43 | 64 | 66 | 56 | Хороший launcher, но после `start()` pool почти raw-process. |
| NuProcess | 136 | workaround | 36 | 57 | 72 | 55 | Технически мощный I/O слой, ergonomics тяжелые из-за callback state machine. |
| Pty4J | 126 | workaround | 28 | 50 | 61 | 46 | Полезен только если каждый worker требует PTY; pool не входит в scope. |
| ExpectIt | 120 | workaround | 34 | 55 | 58 | 48 | Удобен для expect-паттернов внутри worker, но не для pool/lifecycle. |

Лучший fit: iCLI rewrite, потому что `PooledLineSession` напрямую моделирует warm pool, скрывает lease и различает
acquire/request failures. Самый короткий код: iCLI rewrite; остальные кандидаты требуют примерно на порядок больше
пользовательского кода даже в сжатом sketch. Самый надежный contract среди внешних вариантов: JDK `ProcessBuilder` как
низкоуровневый baseline, но только при условии, что команда сама реализует и тестирует pool state machine, stream
draining, timeout retirement и cleanup. NuProcess может быть сильнее для high-throughput I/O, но human-factor цена выше.
