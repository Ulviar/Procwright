# Handle concurrent independent requests

A [worker service](wrap-cli-tool.md) already reuses one process and serializes requests. Add a pool when independent calls
should run concurrently in interchangeable workers. Keep one session when later calls rely on a particular worker's state.

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
Each call gets a worker, performs one exchange, and returns the worker to the pool or retires it after failure. The pool
creates at most two workers, on demand, and reuses them for later requests.

The `command` still launches the bundled JSON Lines worker. To use a compatible worker of your own:

```shell
./gradlew -q demoPool --args='my-worker --json-lines'
```

## Know the boundaries

- Configure worker settings before `pooled()` and pool settings after it.
- Waiting for a worker, processing a request, and resetting the worker have separate timeouts.
- `close()` stops new requests, lets active requests finish, and closes workers. It waits at most 15 seconds by default;
  a timeout reports failure while cleanup continues.
- Calls may use different workers. Configure reset or health hooks only when your worker needs them; a pool cannot preserve
  state tied to one worker across calls.

See [pooling contracts](../scenarios/pooling.md) for readiness, hooks, metrics, and close failures.
[Line pools](../examples/java/io/github/ulviar/procwright/examples/LinePoolExample.java) use the same lifecycle.
