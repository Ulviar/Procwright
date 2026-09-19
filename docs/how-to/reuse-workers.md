# Handle concurrent independent requests

A [direct worker service](wrap-cli-tool.md) already reuses one process and serializes requests. Add a pool when calls are
independent and multiple interchangeable workers should handle them concurrently. Keep one session when later calls
rely on the state of a particular worker.

## Run the pool demo

```shell
./gradlew -q demoPool
```

On Windows, use `.\gradlew.bat -q demoPool`. It prints the same two results as `demoWorker`, using concurrent callers:

```text
hello: Metrics[codePoints=5, utf8Bytes=5]
café: Metrics[codePoints=4, utf8Bytes=5]
```

## Extend the same configuration

Use `TextWorkerService.draft(command)` from the previous walkthrough. The pool adds `.pooled().withMaxSize(2).open()`:

<!-- procwright-example: examples/integrations/io/github/ulviar/procwright/examples/integration/WorkerPoolExample.java#pool -->
```java
try (var pool = TextWorkerService.draft(command).pooled().withMaxSize(2).open();
        var requests = Executors.newVirtualThreadPerTaskExecutor()) {
    var first = requests.submit(() -> pool.request(new TextWorkerService.Request("hello")));
    var second = requests.submit(() -> pool.request(new TextWorkerService.Request("café")));
    System.out.println("hello: " + first.get(15, TimeUnit.SECONDS));
    System.out.println("café: " + second.get(15, TimeUnit.SECONDS));
}
```

[Complete pool client](../examples/integrations/io/github/ulviar/procwright/examples/integration/WorkerPoolExample.java).
The executor here submits only these two requests. Each call acquires a worker, performs one exchange, and returns or
retires it. The maximum is two workers; the pool creates them on demand and can reuse a worker for later requests.

The `command` still launches the bundled JSON Lines worker. To use a compatible worker of your own:

```shell
./gradlew -q demoPool --args='my-worker --json-lines'
```

## Know the boundaries

- Configure worker settings before `pooled()` and pool settings after it.
- Acquisition, request, and reset have separate budgets; there is no single pooled-call deadline.
- `close()` stops new requests and waits for logical worker drain, for at most 15 seconds by default.
- Calls may use different workers. The pool supplies no affinity; configure reset/health hooks when your worker needs them.

See [pooling contracts](../scenarios/pooling.md) for timeouts, readiness, hooks, metrics, and drain failure handling.
[Line pools](../examples/java/io/github/ulviar/procwright/examples/LinePoolExample.java) use the same lifecycle.
