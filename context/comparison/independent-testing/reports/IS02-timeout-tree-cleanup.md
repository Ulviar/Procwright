# IS02: Hung process and process-tree cleanup

## Scenario

Сценарий моделирует команду, которая успела вывести диагностический маркер `started`, породила дочерний процесс,
напечатала `child:<pid>` и зависла. Реализация должна:

- вернуть timeout outcome, а не просто бросить неразобранную ошибку;
- завершить parent и descendant processes в ограниченное время;
- сохранить bounded stdout/stderr diagnostics и truncation metadata;
- позволить проверить, что дочерний процесс не пережил cleanup.

LOC ниже считается как nonblank noncomment lines of code для core implementation. Реализации helper-ов
`hungTreeCommand()`, `childPid(...)`, `isAliveEventually(...)` считаются тестовым harness и не включаются. Если кандидат
требует пользовательский `BoundedCapture` или `stopTree(ProcessHandle, ...)`, эти helper-и включены в LOC, потому что
именно они становятся владельцами инвариантов bounded diagnostics и process-tree cleanup.

## Implementations

### iCLI rewrite

```java
List<DiagnosticEvent> events = new CopyOnWriteArrayList<>();
CommandService service = CommandService.forCommand(command.getFirst())
        .withDiagnostics(DiagnosticsOptions.defaults().withListener(events::add));

CommandResult result = service.run(call -> call.args(command.subList(1, command.size()))
        .timeout(Duration.ofMillis(120))
        .capture(CapturePolicy.bounded(4096))
        .shutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(50), Duration.ofMillis(500))));

long child = childPid(result.stdout());
assertTrue(result.timedOut());
assertTrue(result.stdout().contains("started"));
assertFalse(result.stdoutTruncated());
assertFalse(isAliveEventually(child));
assertTrue(events.stream().anyMatch(e -> e.type() == DiagnosticEventType.TIMEOUT_REACHED));
assertTrue(events.stream().anyMatch(e -> e.type() == DiagnosticEventType.SHUTDOWN_REQUESTED));
assertTrue(events.stream().anyMatch(e -> e.type() == DiagnosticEventType.PROCESS_EXITED));
```

LOC: 14
Status: implemented
API: 94
Docs: 88
Library: 90
Scenario: 91
Notes: Сценарий выражается напрямую через `run`, `timeout`, `CapturePolicy` и `ShutdownPolicy`. `CommandResult` явно
несет `timedOut`, captured output и truncation flags; diagnostics events сохраняют lifecycle без raw argv/env/output.
В runtime tree cleanup принадлежит библиотеке через `ProcessHandle.descendants()`, staged destroy/kill и bounded drain.
Ограничение: гарантия tree cleanup остается в пределах возможностей JDK `ProcessHandle` и платформенной модели
descendants.

### JDK ProcessBuilder

```java
BoundedCapture stdout = new BoundedCapture(4096);
BoundedCapture stderr = new BoundedCapture(4096);
Process process = new ProcessBuilder(command).start();

try (ExecutorService io = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().factory())) {
    process.getOutputStream().close();
    Future<?> out = io.submit(() -> process.getInputStream().transferTo(stdout));
    Future<?> err = io.submit(() -> process.getErrorStream().transferTo(stderr));
    boolean timedOut = !process.waitFor(120, TimeUnit.MILLISECONDS);
    if (timedOut && !stopTree(process.toHandle(), Duration.ofMillis(50), Duration.ofMillis(500))) {
        throw new TimeoutException("process tree cleanup timed out");
    }
    await(out, Duration.ofSeconds(1));
    await(err, Duration.ofSeconds(1));
    RunDiagnostics diagnostics = new RunDiagnostics(
            timedOut, process.exitValue(), stdout.bytes(), stderr.bytes(), stdout.truncated(), stderr.truncated());
    long child = childPid(diagnostics.stdoutText());
    assertTrue(diagnostics.timedOut());
    assertFalse(isAliveEventually(child));
}
```

LOC: 56
Status: implemented
API: 58
Docs: 72
Library: 72
Scenario: 66
Notes: JDK дает все низкоуровневые примитивы: direct argv, `waitFor(timeout)`, streams и `ProcessHandle.descendants()`.
Но пользователь сам проектирует output pumps, bounded capture, timeout mapping, staged tree shutdown, drain timeout,
exception policy и diagnostic object. Самый надежный вариант на JDK возможен, но это уже custom process harness.

### Apache Commons Exec

```java
BoundedCapture stdout = new BoundedCapture(4096);
BoundedCapture stderr = new BoundedCapture(4096);
PumpStreamHandler streams = new PumpStreamHandler(stdout, stderr);
streams.setStopTimeout(Duration.ofSeconds(1));

TrackingExecutor executor = new TrackingExecutor(streams);
executor.setExitValues(null);
DefaultExecuteResultHandler done = new DefaultExecuteResultHandler();
executor.execute(commandLine(command), environment, done);

done.waitFor(Duration.ofMillis(120));
boolean timedOut = !done.hasResult();
if (timedOut && !stopTree(executor.process().toHandle(), Duration.ofMillis(50), Duration.ofMillis(500))) {
    throw new TimeoutException("process tree cleanup timed out");
}
if (!timedOut && done.getException() != null) {
    throw done.getException();
}
RunDiagnostics diagnostics = new RunDiagnostics(
        timedOut, timedOut ? -1 : done.getExitValue(),
        stdout.bytes(), stderr.bytes(), stdout.truncated(), stderr.truncated());
long child = childPid(diagnostics.stdoutText());
assertFalse(isAliveEventually(child));
```

LOC: 64
Status: workaround
API: 62
Docs: 68
Library: 72
Scenario: 67
Notes: Commons Exec хорошо закрывает stream pumping и direct-process watchdog use case, но IS02 требует больше.
`ExecuteWatchdog` убивает watched process, а не дает явный process-tree cleanup contract; для корректного child cleanup
приходится обходить штатный sync API через async handler, subclass `DefaultExecutor` для доступа к `Process`, собственный
timeout и собственный `stopTree`. Это рабочий путь, но инварианты сценария уже живут не в библиотеке.

### ZeroTurnaround zt-exec

```java
BoundedCapture stdout = new BoundedCapture(4096);
BoundedCapture stderr = new BoundedCapture(4096);
AtomicBoolean timedOut = new AtomicBoolean();

try {
    new ProcessExecutor()
            .command(command)
            .redirectOutput(stdout)
            .redirectError(stderr)
            .exitValueAny()
            .timeout(120, TimeUnit.MILLISECONDS)
            .closeTimeout(1, TimeUnit.SECONDS)
            .stopper(process -> {
                timedOut.set(true);
                stopTreeUnchecked(process.toHandle(), Duration.ofMillis(50), Duration.ofMillis(500));
            })
            .execute();
} catch (TimeoutException expected) {
    timedOut.set(true);
}

RunDiagnostics diagnostics = new RunDiagnostics(
        timedOut.get(), -1, stdout.bytes(), stderr.bytes(), stdout.truncated(), stderr.truncated());
long child = childPid(diagnostics.stdoutText());
assertFalse(isAliveEventually(child));
```

LOC: 50
Status: workaround
API: 76
Docs: 70
Library: 72
Scenario: 73
Notes: Лучший внешний ergonomic fit: fluent timeout, output redirection, `closeTimeout` и pluggable `ProcessStopper`
сильно сокращают call-site. Но process-tree semantics все равно пользовательские: `ProcessStopper` должен знать про
`ProcessHandle.descendants()`, staged kill и cleanup failure. Timeout приходит как exception, поэтому диагностический
result также приходится собирать отдельно.

### NuProcess

```java
BoundedCapture stdout = new BoundedCapture(4096);
BoundedCapture stderr = new BoundedCapture(4096);
TreeAwareNuHandler handler = new TreeAwareNuHandler(stdout, stderr);
NuProcessBuilder builder = new NuProcessBuilder(command);
builder.environment().putAll(environment);
builder.setProcessListener(handler);

NuProcess process = builder.start();
int exit = process.waitFor(120, TimeUnit.MILLISECONDS);
boolean timedOut = process.isRunning();
if (timedOut) {
    ProcessHandle root = ProcessHandle.of(process.getPID()).orElseThrow();
    if (!stopTree(root, Duration.ofMillis(50), Duration.ofMillis(500))) {
        process.destroy(true);
        throw new TimeoutException("process tree cleanup timed out");
    }
    exit = process.waitFor(500, TimeUnit.MILLISECONDS);
}

RunDiagnostics diagnostics = new RunDiagnostics(
        timedOut, exit, stdout.bytes(), stderr.bytes(), stdout.truncated(), stderr.truncated());
long child = childPid(diagnostics.stdoutText());
assertFalse(isAliveEventually(child));
```

LOC: 71
Status: workaround
API: 54
Docs: 57
Library: 66
Scenario: 59
Notes: NuProcess полезен, когда нужна callback/non-blocking I/O model, но IS02 не выигрывает от этой модели. Нужно писать
handler, управлять stdin close, вручную ограничивать buffers, мапить timeout и использовать `getPID()` плюс JDK
`ProcessHandle` для tree cleanup. Failure semantics callback-ов и cleanup path сложнее, чем у one-shot API.

### Pty4J

```java
BoundedCapture transcript = new BoundedCapture(4096);
PtyProcess process = new PtyProcessBuilder()
        .setCommand(command.toArray(String[]::new))
        .setEnvironment(environment)
        .start();

try (ExecutorService io = Executors.newSingleThreadExecutor()) {
    process.getOutputStream().close();
    Future<?> pump = io.submit(() -> process.getInputStream().transferTo(transcript));
    boolean timedOut = !process.waitFor(120, TimeUnit.MILLISECONDS);
    if (timedOut && !stopTree(process.toHandle(), Duration.ofMillis(50), Duration.ofMillis(500))) {
        process.destroyForcibly();
        throw new TimeoutException("PTY process tree cleanup timed out");
    }
    await(pump, Duration.ofSeconds(1));
    RunDiagnostics diagnostics = new RunDiagnostics(
            timedOut, process.exitValue(), transcript.bytes(), new byte[0], transcript.truncated(), false);
    long child = childPid(diagnostics.stdoutText());
    assertFalse(isAliveEventually(child));
}
```

LOC: 48
Status: workaround
API: 38
Docs: 45
Library: 50
Scenario: 44
Notes: Pty4J решает terminal transport, а не one-shot timeout supervision. Для IS02 PTY добавляет native/platform
поверхность, меняет stream semantics и часто превращает stdout/stderr в terminal transcript. Cleanup снова держится на
JDK `ProcessHandle`, bounded diagnostics на пользовательском capture, а не на Pty4J contract.

### ExpectIt

```java
// unsupported: ExpectIt works over already-open streams.
// It has no process launch, process timeout, bounded cleanup, or process-tree API.
```

LOC: 0
Status: unsupported
API: 12
Docs: 55
Library: 42
Scenario: 33
Notes: ExpectIt может помочь после того, как другой owner уже создал process и streams, но в IS02 главный риск находится
ниже prompt layer: timeout, cleanup, child processes и bounded process diagnostics. Обертка `ProcessBuilder + ExpectIt`
не будет оценкой ExpectIt как process runtime; все существенные гарантии придется писать в JDK harness.

## Summary

Лучший fit: iCLI rewrite. Он единственный выражает IS02 как обычный one-shot сценарий: timeout, capture, diagnostics и
shutdown policy находятся в публичном API, а tree cleanup принадлежит runtime.

Самый короткий рабочий код: iCLI rewrite. `ExpectIt` имеет LOC 0 только потому, что сценарий unsupported; среди
реализуемых вариантов iCLI заметно короче и не прячет обязательные инварианты в пользовательские helpers.

Самый сильный внешний кандидат: zt-exec. У него хороший fluent one-shot API и `ProcessStopper`, но bounded diagnostics,
typed timeout result и process-tree contract остаются ответственностью caller-а.

Самый надежный низкоуровневый fallback: JDK `ProcessBuilder` + `ProcessHandle`, если команда готова владеть собственным
process harness. Commons Exec, NuProcess и Pty4J могут быть доведены до рабочего поведения, но для IS02 это workaround,
а не естественный сценарий библиотеки.
