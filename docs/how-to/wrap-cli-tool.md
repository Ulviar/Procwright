# Use a JSON Lines worker as a service

If your CLI accepts repeated JSON Lines requests on stdin and replies on stdout, wrap it in a Java service with typed
methods. This example uses the JSON Lines adapter from `procwright-integrations`; its worker counts Unicode code points
and UTF-8 bytes. For a tool that exits after each command, use [`run()`](run-finite-command.md).

## Run it

From the checkout root with JDK 25:

```shell
./gradlew -q demoWorker
```

On Windows, use `.\gradlew.bat -q demoWorker`. Expected output:

```text
hello: Metrics[codePoints=5, utf8Bytes=5]
café: Metrics[codePoints=4, utf8Bytes=5]
```

Both requests go through the same session. The worker stays alive until the service is closed.

## Keep the service open across calls

The client creates the service once, uses it twice, and closes it:

<!-- procwright-example: examples/integrations/io/github/ulviar/procwright/examples/integration/WorkerServiceExample.java#reuse -->
```java
try (var service = new TextWorkerService(command)) {
    System.out.println("hello: " + service.analyze("hello"));
    System.out.println("café: " + service.analyze("café"));
}
```

In the demo, `command` launches the [included worker](../examples/integrations/io/github/ulviar/procwright/examples/integration/JsonLinesTextWorker.java).
In your application, supply a `CommandSpec` for your executable. Create the service once in the component that uses it
and close it when that component stops. Creating it inside each `analyze` call would launch a new process each time.

These are the lifecycle methods inside [TextWorkerService.java](../examples/integrations/io/github/ulviar/procwright/examples/integration/TextWorkerService.java):

<!-- procwright-example: examples/integrations/io/github/ulviar/procwright/examples/integration/TextWorkerService.java#service -->
```java
private final ProtocolSession<Request, Metrics> session;

public TextWorkerService(CommandSpec command) {
    session = draft(command).open();
}

public Metrics analyze(String text) {
    return session.request(new Request(text));
}

@Override
public void close() {
    session.close();
}
```

`Request` contains `String text`; `Metrics` contains `int codePoints` and `int utf8Bytes`. Both records are declared in the
same source file. One session serializes concurrent calls, so they cannot mix protocol messages.

## Map your domain types

The service's `draft` method maps Java records to JSON and selects JSON Lines framing:

<!-- procwright-example: examples/integrations/io/github/ulviar/procwright/examples/integration/TextWorkerService.java#protocol -->
```java
public static ProtocolSessionScenario.Draft<Request, Metrics> draft(CommandSpec command) {
    var adapters = ProtocolAdapters.typedJson(
            (Request request) -> JsonNodeFactory.instance.objectNode().put("text", request.text()),
            response -> new Metrics(
                    response.required("codePoints").intValue(),
                    response.required("utf8Bytes").intValue()),
            ProtocolAdapters.jsonLines(64 * 1024));
    return Procwright.command(command).protocolSession(adapters);
}
```

The worker reads a JSON value followed by LF, for example `{"text":"hello"}`, and replies with
`{"codePoints":5,"utf8Bytes":5}` followed by LF. It must flush each reply and reserve stdout for protocol messages;
send logs to stderr. JSON Lines uses strict UTF-8.

The adapter caps each response line, including LF, at 64 KiB. Requests have a five-second timeout by default. Core byte
limits also bound messages and unread output; see [protocol defaults](../reference/defaults.md#protocol-sessions).

## Connect your worker

A worker with this same JSON contract can replace the bundled one:

```shell
./gradlew -q demoWorker --args='my-worker --json-lines'
```

For a different JSON schema, edit the two records and the encode/decode functions. Copy the
[client](../examples/integrations/io/github/ulviar/procwright/examples/integration/WorkerServiceExample.java) and
[service](../examples/integrations/io/github/ulviar/procwright/examples/integration/TextWorkerService.java) into your application.
In `WorkerServiceExample` or `WorkerPoolExample`, replace
`JsonLinesTextWorker.command(args)` with `CommandSpec.of("my-worker").withArgs("--json-lines")` to remove the dependency
on the bundled worker.

For a separate application, add `procwright-integrations`; it brings in core and Jackson transitively.
[Dependency setup](../release/installation.md#optional-modules) gives the build snippets.

Once a request starts its turn, a timeout or protocol failure closes the session. Replace the service before another
call; a failed request may already have had an effect in the worker, so retry only when your operation allows it.
A timeout while waiting behind another call leaves a healthy session open. See
[protocol request failures](../scenarios/protocol-session.md#request-failures) for the boundary and
[results and errors](../reference/results-and-errors.md#sessions) for exception reasons.

Next: [use the same protocol configuration with a pool](reuse-workers.md) when requests are independent and concurrent.
For another wire format, choose a [ready-made adapter](../scenarios/integrations.md) before writing custom framing.
