# IS03: Streaming log follower

## Scenario

Реальный кейс: `kubectl logs -f`, `tail -f`, dev server logs.

Сценарий оценивает, насколько библиотека позволяет получать stdout/stderr chunks пока процесс еще жив, завершать
follow-сессию по timeout или cancel и иметь понятную listener failure semantics. В sketches ниже предполагаются уже
подготовленные `command`, `env`, `workDir`, `timeout`, `grace`, `cancel`, `listener` и простые доменные типы
`Chunk`/`Source`; они не входят в LOC. LOC — nonblank noncomment lines shown for the core implementation.

## Implementations

### iCLI rewrite

```java
CommandService kubectl = new CommandService(CommandSpec.of("kubectl"), RunOptions.defaults(),
        SessionOptions.defaults(), LineSessionOptions.defaults(),
        StreamOptions.defaults().withTimeout(timeout));
CompletableFuture<Void> cancel = new CompletableFuture<>();
try (StreamSession stream = kubectl.listen(call -> call.args("logs", "-f", "deploy/app")
        .onOutput(listener::accept))) {
    cancel.thenRun(stream::close);
    StreamExit exit = stream.onExit().join();
    if (exit.timedOut() || exit.closed()) handleStop(exit.diagnostics());
} catch (CompletionException exception) {
    if (exception.getCause() instanceof StreamException failure) {
        handleListenerFailure(failure.diagnostics(), failure.getCause());
    } else {
        throw exception;
    }
}
```

LOC: 16
Status: implemented
API: 93
Docs: 84
Library: 84
Scenario: 88
Notes: `listen` напрямую выражает log-following: separate stdout/stderr chunks, synchronous serialized listener,
bounded diagnostics, default stdin close, `StreamSession.close()` for cancel, timeout in `StreamOptions`/per-call
override. Listener `RuntimeException` is not swallowed: `onExit()` completes exceptionally with `StreamException` and retained
diagnostics. Минусы: проект еще `0.0.0-SNAPSHOT`, API молодое, документация сценарная и тестовая, но не release-grade.

### JDK ProcessBuilder

```java
Process process = new ProcessBuilder(command).directory(workDir).start();
ExecutorService pumps = Executors.newFixedThreadPool(2);
AtomicReference<Throwable> listenerFailure = new AtomicReference<>();
Runnable stop = () -> {
    process.descendants().forEach(ProcessHandle::destroyForcibly);
    process.destroyForcibly();
};
Future<?> stdout = pumps.submit(() -> pump(process.getInputStream(), Source.STDOUT, listener, listenerFailure, stop));
Future<?> stderr = pumps.submit(() -> pump(process.getErrorStream(), Source.STDERR, listener, listenerFailure, stop));
ScheduledFuture<?> timeoutTask = scheduler.schedule(stop, timeout.toMillis(), MILLISECONDS);
cancel.thenRun(stop);
boolean exited = process.waitFor(timeout.plus(grace).toMillis(), MILLISECONDS);
timeoutTask.cancel(false);
stdout.get(1, SECONDS);
stderr.get(1, SECONDS);
pumps.shutdownNow();
if (!exited) throw new TimeoutException("stream timeout");
if (listenerFailure.get() != null) throw new ListenerFailedException(listenerFailure.get());
return process.exitValue();

static void pump(InputStream stream, Source source, Consumer<Chunk> listener,
        AtomicReference<Throwable> failure, Runnable stop) {
    byte[] buffer = new byte[8192];
    try (stream) {
        for (int n; (n = stream.read(buffer)) >= 0; ) {
            byte[] bytes = Arrays.copyOf(buffer, n);
            try {
                synchronized (listener) {
                    listener.accept(new Chunk(source, bytes));
                }
            } catch (Throwable throwable) {
                if (failure.compareAndSet(null, throwable)) stop.run();
                return;
            }
        }
    } catch (IOException exception) {
        if (failure.get() == null) throw new UncheckedIOException(exception);
    }
}
```

LOC: 38
Status: workaround
API: 57
Docs: 78
Library: 70
Scenario: 66
Notes: JDK gives the necessary primitives: direct argv, stdout/stderr pipes, `waitFor(timeout)`, `destroy`,
`destroyForcibly`, `ProcessHandle.descendants()`. Human-factor cost is high: user owns pump threads, serialization,
listener failure capture, timeout race handling, cancel semantics, stream close noise and process-tree policy. Official
docs are strong for primitives, but they do not define this scenario as a contract.

### Apache Commons Exec

```java
AtomicReference<Throwable> listenerFailure = new AtomicReference<>();
ExecuteWatchdog watchdog = ExecuteWatchdog.builder().setTimeout(timeout).get();
DefaultExecuteResultHandler done = new DefaultExecuteResultHandler();
OutputStream stdout = stream(Source.STDOUT, listener, listenerFailure, watchdog::destroyProcess);
OutputStream stderr = stream(Source.STDERR, listener, listenerFailure, watchdog::destroyProcess);
DefaultExecutor executor = DefaultExecutor.builder()
        .setExecuteStreamHandler(new PumpStreamHandler(stdout, stderr, InputStream.nullInputStream()))
        .setWorkingDirectory(workDir)
        .get();
executor.setExitValues(null);
executor.setWatchdog(watchdog);
cancel.thenRun(watchdog::destroyProcess);
executor.execute(commandLine(command), env, done);
done.waitFor(timeout.plus(grace).toMillis());
watchdog.checkException();
if (watchdog.killedProcess()) throw new TimeoutException("stream timeout");
if (listenerFailure.get() != null) throw new ListenerFailedException(listenerFailure.get());
return done.getExitValue();

static OutputStream stream(Source source, Consumer<Chunk> listener,
        AtomicReference<Throwable> failure, Runnable stop) {
    return new OutputStream() {
        @Override public void write(byte[] bytes, int off, int len) throws IOException {
            if (len == 0) return;
            try {
                synchronized (listener) {
                    listener.accept(new Chunk(source, Arrays.copyOfRange(bytes, off, off + len)));
                }
            } catch (Throwable throwable) {
                if (failure.compareAndSet(null, throwable)) stop.run();
                throw new IOException("listener failed", throwable);
            }
        }
        @Override public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }
    };
}
```

LOC: 37
Status: workaround
API: 66
Docs: 76
Library: 78
Scenario: 73
Notes: `PumpStreamHandler` and `ExecuteWatchdog` are close to the needed shape: output is drained on background pump
threads and timeout/cancel can go through `destroyProcess()`. The missing part is listener semantics: callbacks are
modeled as `OutputStream.write`, so listener exceptions must be converted to `IOException`, stored out-of-band and tied
manually to process shutdown. Mature library, but process-tree cleanup and typed stream chunks remain caller-owned.

### ZeroTurnaround zt-exec

```java
AtomicReference<Process> processRef = new AtomicReference<>();
AtomicReference<Throwable> listenerFailure = new AtomicReference<>();
OutputStream stdout = stream(Source.STDOUT, listener, listenerFailure, processRef);
OutputStream stderr = stream(Source.STDERR, listener, listenerFailure, processRef);
ProcessExecutor executor = new ProcessExecutor()
        .command(command)
        .directory(workDir)
        .environment(env)
        .redirectInput(InputStream.nullInputStream())
        .redirectOutput(stdout)
        .redirectError(stderr)
        .exitValueAny()
        .timeout(timeout.toMillis(), MILLISECONDS)
        .listener(new ProcessListener() {
            @Override public void afterStart(Process process, ProcessExecutor executor) {
                processRef.set(process);
            }
        });
StartedProcess started = executor.start();
cancel.thenRun(() -> Optional.ofNullable(processRef.get()).ifPresent(Process::destroyForcibly));
ProcessResult result = started.getFuture().get(timeout.plus(grace).toMillis(), MILLISECONDS);
if (listenerFailure.get() != null) throw new ListenerFailedException(listenerFailure.get());
return result.getExitValue();

static OutputStream stream(Source source, Consumer<Chunk> listener,
        AtomicReference<Throwable> failure, AtomicReference<Process> processRef) {
    return new OutputStream() {
        @Override public void write(byte[] bytes, int off, int len) throws IOException {
            if (len == 0) return;
            try {
                synchronized (listener) {
                    listener.accept(new Chunk(source, Arrays.copyOfRange(bytes, off, off + len)));
                }
            } catch (Throwable throwable) {
                failure.compareAndSet(null, throwable);
                Optional.ofNullable(processRef.get()).ifPresent(Process::destroyForcibly);
                throw new IOException("listener failed", throwable);
            }
        }
        @Override public void write(int value) throws IOException {
            write(new byte[] {(byte) value}, 0, 1);
        }
    };
}
```

LOC: 43
Status: workaround
API: 72
Docs: 80
Library: 72
Scenario: 74
Notes: Fluent API is pleasant for command/env/timeout and docs include examples for timeout, background start and
line-by-line output. For this exact scenario, full semantics still require glue: a custom `OutputStream`, process ref
capture for cancel/listener failure, and out-of-band exception state. Good ergonomics for one-shot execution; less
convincing as a long-lived follow contract, especially around process-tree policy and callback failure propagation.

### NuProcess

```java
AtomicReference<Throwable> listenerFailure = new AtomicReference<>();
AtomicReference<NuProcess> processRef = new AtomicReference<>();
NuProcessBuilder builder = new NuProcessBuilder(command);
builder.environment().putAll(env);
builder.setCwd(workDir);
builder.setProcessListener(new NuAbstractProcessHandler() {
    @Override public void onStart(NuProcess process) {
        processRef.set(process);
        process.closeStdin(false);
    }
    @Override public void onStdout(ByteBuffer buffer, boolean closed) {
        deliver(Source.STDOUT, buffer);
    }
    @Override public void onStderr(ByteBuffer buffer, boolean closed) {
        deliver(Source.STDERR, buffer);
    }
    private void deliver(Source source, ByteBuffer buffer) {
        if (!buffer.hasRemaining()) return;
        byte[] bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        try {
            synchronized (listener) {
                listener.accept(new Chunk(source, bytes));
            }
        } catch (Throwable throwable) {
            listenerFailure.compareAndSet(null, throwable);
            Optional.ofNullable(processRef.get()).ifPresent(process -> process.destroy(true));
        }
    }
});
NuProcess process = builder.start();
cancel.thenRun(() -> process.destroy(true));
int exit = process.waitFor(timeout.toMillis(), MILLISECONDS);
if (process.isRunning()) {
    process.destroy(true);
    throw new TimeoutException("stream timeout");
}
if (listenerFailure.get() != null) throw new ListenerFailedException(listenerFailure.get());
return exit;
```

LOC: 39
Status: implemented
API: 78
Docs: 68
Library: 76
Scenario: 75
Notes: The callback model is naturally streaming and exposes stdout/stderr separately without user-created pump
threads. Timeout/cancel and listener failure still require user policy code around `waitFor`, `destroy(true)` and stored
failure state. Native/non-blocking design is a strong fit for high-volume streaming, but callback contracts require
discipline: slow listener behavior, exception propagation, decoding, cancellation race handling and tree cleanup are not
packaged as a scenario.

### Pty4J

```java
PtyProcess process = new PtyProcessBuilder()
        .setCommand(command.toArray(String[]::new))
        .setEnvironment(env)
        .setDirectory(workDir.toString())
        .start();
ExecutorService pump = Executors.newSingleThreadExecutor();
AtomicReference<Throwable> listenerFailure = new AtomicReference<>();
Future<?> terminal = pump.submit(() -> pump(process.getInputStream(), Source.TERMINAL, listener, listenerFailure,
        process::destroyForcibly));
ScheduledFuture<?> timeoutTask = scheduler.schedule(process::destroyForcibly, timeout.toMillis(), MILLISECONDS);
cancel.thenRun(process::destroyForcibly);
boolean exited = process.waitFor(timeout.plus(grace).toMillis(), MILLISECONDS);
timeoutTask.cancel(false);
terminal.get(1, SECONDS);
pump.shutdownNow();
if (!exited) throw new TimeoutException("stream timeout");
if (listenerFailure.get() != null) throw new ListenerFailedException(listenerFailure.get());
return process.exitValue();

static void pump(InputStream stream, Source source, Consumer<Chunk> listener,
        AtomicReference<Throwable> failure, Runnable stop) {
    byte[] buffer = new byte[8192];
    try (stream) {
        for (int n; (n = stream.read(buffer)) >= 0; ) {
            try {
                listener.accept(new Chunk(source, Arrays.copyOf(buffer, n)));
            } catch (Throwable throwable) {
                if (failure.compareAndSet(null, throwable)) stop.run();
                return;
            }
        }
    } catch (IOException exception) {
        if (failure.get() == null) throw new UncheckedIOException(exception);
    }
}
```

LOC: 34
Status: workaround
API: 38
Docs: 58
Library: 56
Scenario: 49
Notes: Pty4J is a PTY transport, not a log-following abstraction. It can stream terminal output, but PTY changes child
behavior and collapses output into a terminal stream; faithful stdout/stderr chunk attribution is not available. Timeout,
cancel and listener failure all need manual harness code. Useful when the command requires a terminal; poor fit for
ordinary `kubectl logs -f`/`tail -f` where pipe semantics and stderr separation matter.

### ExpectIt

```java
Process process = new ProcessBuilder(command).start();
try (Expect expect = new ExpectBuilder()
        .withInputs(process.getInputStream(), process.getErrorStream())
        .withOutput(process.getOutputStream())
        .withTimeout(200, MILLISECONDS)
        .build()) {
    cancel.thenRun(process::destroyForcibly);
    while (process.isAlive()) {
        Result result = expect.expect(Matchers.anyString());
        listener.accept(new Chunk(Source.UNKNOWN, result.getInput().getBytes(UTF_8)));
    }
}
```

LOC: 0
Status: unsupported
API: 16
Docs: 52
Library: 42
Scenario: 34
Notes: Sketch above is only an illustrative workaround and is not counted as a faithful implementation. ExpectIt is an
expect/matcher helper over streams, not a process lifecycle or log-following runtime. It does not provide natural
stdout/stderr chunk callbacks, backpressure semantics, listener failure contract, process timeout/cancel ownership or
bounded diagnostics for continuous logs. It is appropriate for prompt automation, not for IS03.

## Summary

Best fit: iCLI rewrite. It is the only candidate where IS03 is a named workflow rather than a user-built harness:
`listen` owns process lifecycle, stream draining, timeout/cancel path, bounded diagnostics and listener failure
propagation.

Best external fit: NuProcess. Its native callback model is closest to live stdout/stderr chunk delivery and avoids
manual pump threads, but caller still owns listener exception semantics, timeout/cancel policy and diagnostics.

Shortest faithful code: iCLI rewrite at 16 LOC. zt-exec has concise happy-path examples, but the moment listener failure
and cancel semantics are required, the glue grows beyond the JDK/Commons-style harness.

Most reliable contract: iCLI rewrite for this scenario. JDK is the most mature primitive layer, Apache Commons Exec and
zt-exec are productive one-shot wrappers, and NuProcess is strong for high-volume async IO, but none of them packages
the complete log-follower contract. Pty4J is a PTY workaround with lost stderr attribution. ExpectIt is unsupported for
this scenario.
