# Choose a Process Scenario

Use this guide when you know the shape of the external process but have not chosen the iCLI scenario yet.

## Version or availability probes

Choose `run` for commands such as `git --version`, `docker info`, `java --version`, or a tool-specific `doctor` command.

Keep executable discovery in the application layer. In `0.1.0`, iCLI launches the command it is given; it does not own
PATH lookup, Windows extension probing, package-manager installation, or toolchain discovery.

Recommended shape:

- create a `CommandService` for the executable or resolved path;
- set a short timeout;
- use bounded capture;
- convert unsuccessful results with `CommandResult.toException()` when fail-fast flow is wanted.

```java
CommandService java = Icli.command("java");

CommandResult result = java.run().execute("--version");

if (!result.succeeded()) {
    throw result.toException();
}
```

See [Run a finite command](run-finite-command.md) and [`run`](../scenarios/run.md).

## Release or build automation command

Choose `run` when a release step must complete before the next step starts. This matches common wrappers around Git,
Maven, Gradle, Docker, code generators, and validators.

Use stable command defaults for working directory and environment. Put per-step arguments, timeout, capture, and
shutdown policy in the scenario invocation.

See [Stop hung processes](stop-hung-processes.md), [Command model](../reference/command-model.md), and
[Policies](../reference/policies.md).

## Long-running log follower

Choose `listen` when the process is a stream source: `tail -f`, `kubectl logs -f`, a local server log, or a watcher.

The listener should be bounded and fast. Slow listeners create backpressure on the process pipe instead of unbounded
memory growth. If the caller needs to stop watching, close the `StreamSession`.

```java
CommandService tool = Icli.command("tool");

try (StreamSession stream = tool.listen()
        .withArgs("logs", "--follow")
        .onOutput(chunk -> {
            if (chunk.source() == StreamSource.STDERR) {
                System.err.print(chunk.text());
            } else {
                System.out.print(chunk.text());
            }
        })
        .open()) {
    stream.onExit().join();
}
```

See [Follow logs](follow-logs.md) and [Streaming](../scenarios/streaming.md).

## Local daemon startup with readiness check

Choose `listen` when readiness is an external observation such as HTTP polling or a known log line. Choose
`interactive`, `lineSession`, or `protocolSession` readiness probes when readiness can be checked through the worker
protocol itself.

Typical readiness checks are HTTP polling, socket availability, a PID file, a status command, or a known log line. iCLI
should own stream draining, timeout, shutdown, and diagnostics. The application still owns the domain-specific definition
of "ready"; iCLI only owns when the probe runs and how the process is closed on readiness failure.

```java
CommandService server = Icli.command("tool");
AtomicBoolean ready = new AtomicBoolean(false);

try (StreamSession stream = server.listen()
        .withArgs("serve")
        .withTimeout(Duration.ofSeconds(30))
        .onOutput(chunk -> {
            if (chunk.text().contains("ready")) {
                ready.set(true);
            }
        })
        .open()) {
    long deadlineNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();
    while (!ready.get() && System.nanoTime() < deadlineNanos) {
        Thread.sleep(25);
    }
    if (!ready.get()) {
        throw new IllegalStateException("server did not become ready");
    }
}
```

Do not turn this into a raw background `Process` unless the caller truly wants to own every stream and shutdown detail.

## Prompt-driven installer or configurator

Choose `interactive` plus `Expect` when a CLI asks questions and emits prompts.

Keep terminal requirements explicit. Some tools work over ordinary pipes; others require terminal capability and should
be launched with [`TerminalPolicy.REQUIRED`](../scenarios/terminal.md).

See [Automate prompts](automate-prompts.md), [Require a terminal](require-terminal.md), and
[Expect Automation](../scenarios/expect.md).

## Line-oriented worker

Choose `lineSession` when the process behaves like a request/response worker where one request produces one logical
response. Choose `lineSession().pooled()` when worker startup is expensive and the protocol can be reset or checked
reliably between requests.

```java
CommandService repl = Icli.command(CommandSpec.of("tool"));

try (LineSession session = repl.lineSession()
        .withArgs("repl")
        .withRequestTimeout(Duration.ofSeconds(2))
        .open()) {
    LineResponse response = session.request("status");
    if (response.text().isBlank()) {
        throw new IllegalStateException("empty response");
    }
}
```

See [Talk to a line worker](talk-to-line-worker.md), [Reuse workers](reuse-workers.md), and
[Line Sessions](../scenarios/line-session.md).

## Framed or typed protocol worker

Choose `protocolSession` when requests or responses are multi-line, byte-oriented, content-length framed,
delimiter-framed, or mapped to domain types. Choose `protocolSession(factory).pooled()` when startup is expensive and
reset/health semantics are clear.

```java
CommandService worker = Icli.command("tool");
ProtocolAdapter<String, String> adapter = new LengthPrefixedTextAdapter();

try (ProtocolSession<String, String> session = worker.protocolSession(adapter)
        .withArgs("worker")
        .withRequestTimeout(Duration.ofSeconds(2))
        .withOutputBacklogLimit(128 * 1024)
        .withReadiness(ready -> ready.request("ready"))
        .open()) {
    String response = session.request("first line\nsecond line");
    if (response.isBlank()) {
        throw new IllegalStateException("empty response");
    }
}
```

The adapter implementation is shown in [Protocol Sessions](../scenarios/protocol-session.md#example). See also
[Reuse workers](reuse-workers.md) and [Integrations](../scenarios/integrations.md).

## JSON Lines or Content-Length tool adapter

Choose the optional integrations module when a CLI should be treated as a structured adapter rather than raw process
text.

Add `io.github.ulviar:icli-integrations` when using these helpers. See
[optional modules](../release/installation.md#optional-modules).

The adapter layer still builds on core scenarios. It should validate output as untrusted data and keep cancellation,
diagnostics, and protocol bounds explicit.

```java
CommandService service = Icli.command("tool");

try (LineSession lineSession = service.lineSession().withArg("json-worker").open();
        JsonLineSession json = JsonLineSession.over(lineSession)) {
    CommandBackedTool<String, JsonValue> tool = CommandBackedTool.jsonLine(
            json, input -> JsonValue.object(Map.of("input", JsonValue.string(input))), Function.identity());

    ToolCallResult<JsonValue> result = tool.call("payload");
    result.value().ifPresent(System.out::println);
}
```

See [Wrap a CLI tool](wrap-cli-tool.md) and [Integrations](../scenarios/integrations.md).

## Tee output to logs and keep diagnostics

First decide which invariant matters more:

- use `run` when the completed result is the primary artifact;
- use `listen` when real-time output consumption is the primary artifact;
- use diagnostics when lifecycle observability is needed without exposing raw output.

The current public API does not provide a single "run and tee all output while also returning a full captured result"
shortcut. That is intentional in `0.1.0`: output ownership and memory bounds must stay visible.
