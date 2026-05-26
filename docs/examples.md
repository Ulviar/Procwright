# Examples

These selected snippets show the main public workflows in one place. Use the how-to guides when you need task steps,
and the scenario reference when you need exact contracts.

## Core examples

### One-shot command

```java
CommandService git = Icli.command("git");

CommandResult result = git.run().execute("status", "--short");

if (!result.succeeded()) {
    throw result.toException();
}
```

### Line worker

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

### Framed protocol worker

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

### Worker pool

```java
CommandService tool = Icli.command("tool");

try (PooledLineSession pool = tool.lineSession()
        .withArgs("repl")
        .pooled()
        .withMaxSize(4)
        .withWarmupSize(1)
        .withMaxRequestsPerWorker(100)
        .withReset(worker -> worker.request("reset"))
        .open()) {
    LineResponse response = pool.request("status", Duration.ofSeconds(2));
    PooledLineSessionMetrics metrics = pool.metrics();
    if (response.text().isBlank() || metrics.size() > 4) {
        throw new IllegalStateException("unexpected pooled response");
    }
}
```

### Typed protocol worker pool

```java
CommandService worker = Icli.command("tool");

try (PooledProtocolSession<String, String> pool = worker.protocolSession(LengthPrefixedTextAdapter::new)
        .withArgs("worker")
        .withReadiness(ready -> ready.request("ready"))
        .pooled()
        .withMaxSize(4)
        .withWarmupSize(1)
        .withMinIdle(1)
        .open()) {
    String response = pool.request("document\nbody", Duration.ofSeconds(2));
    PooledProtocolSessionMetrics metrics = pool.metrics();
    if (response.isBlank() || metrics.size() > 4) {
        throw new IllegalStateException("unexpected pooled response");
    }
}
```

More task-focused guides:

- [Choose a process scenario](how-to/choose-process-scenario.md)
- [Run a finite command](how-to/run-finite-command.md)
- [Talk to a line worker](how-to/talk-to-line-worker.md)
- [Reuse workers](how-to/reuse-workers.md)

## Integration examples

Add the optional integrations artifact before using these APIs. See
[optional modules](release/installation.md#optional-modules).

```java
CommandService service = Icli.command("tool");

try (LineSession lineSession = service.lineSession(call -> call.args("json-worker"));
        JsonLineSession json = JsonLineSession.over(lineSession)) {
    CommandBackedTool<String, JsonValue> tool = CommandBackedTool.jsonLine(
            json, input -> JsonValue.object(Map.of("input", JsonValue.string(input))), Function.identity());

    ToolCallResult<JsonValue> result = tool.call("payload");
    result.value().ifPresent(System.out::println);
}
```

More task-focused guide: [Wrap a CLI tool](how-to/wrap-cli-tool.md).
