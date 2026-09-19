# Use a JSON Lines worker as a service

Keep one CLI process open across calls to a small Java service. This walkthrough uses the ready-made JSON Lines adapter
from `procwright-integrations`; the included worker counts Unicode code points and UTF-8 bytes.

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

## Give the process one owner

The client creates the service once, uses it twice, and closes it:

<!-- procwright-example: examples/integrations/io/github/ulviar/procwright/examples/integration/WorkerServiceExample.java#reuse -->
```java
try (var service = new TextWorkerService(command)) {
    System.out.println("hello: " + service.analyze("hello"));
    System.out.println("café: " + service.analyze("café"));
}
```

In the demo, `command` launches the [included worker](../examples/integrations/io/github/ulviar/procwright/examples/integration/JsonLinesTextWorker.java).
In your application, supply a `CommandSpec` for your executable. Create the service with its application owner and close
it when that owner stops; do not create a new service inside each business-method call.

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

The service's `draft` method supplies request encoding, response decoding, and the existing JSON Lines transport:

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

The adapter caps a response line, including LF, at 64 KiB. Core defaults additionally bound request bytes, response bytes,
and the five-second request wait. Output buffers follow the response byte limit. Change these only when the worker needs different budgets; see
[protocol defaults](../reference/defaults.md#protocol-sessions).

## Connect your worker

A worker with this same JSON contract can replace the bundled one:

```shell
./gradlew -q demoWorker --args='my-worker --json-lines'
```

For a different JSON schema, edit the two records and the encode/decode functions. The adapter factory already creates
separate protocol state for each session. The [complete client](../examples/integrations/io/github/ulviar/procwright/examples/integration/WorkerServiceExample.java)
and [worker](../examples/integrations/io/github/ulviar/procwright/examples/integration/JsonLinesTextWorker.java) are separate files.
Copy the client and service into your application. In `WorkerServiceExample` or `WorkerPoolExample`, replace
`JsonLinesTextWorker.command(args)` with `CommandSpec.of("my-worker").withArgs("--json-lines")` to remove the dependency
on the bundled worker.

For a separate application, add `procwright-integrations`; it brings in core and Jackson transitively.
[Dependency setup](../release/installation.md#optional-modules) gives the build snippets.

An admitted protocol request failure closes the session. Let the service owner replace it before another call;
[results and errors](../reference/results-and-errors.md#sessions) describes typed reasons.
A timeout while waiting behind another call leaves the session open; see the
[protocol request lifecycle](../scenarios/protocol-session.md) for the admission boundary.

Next: [use the same protocol configuration with a pool](reuse-workers.md) when requests are independent and concurrent.
For another wire format, choose a [ready-made adapter](../scenarios/integrations.md) before writing custom framing.
