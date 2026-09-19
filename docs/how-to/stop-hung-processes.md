# Stop a hung command

Set an execution timeout and allow time for graceful shutdown before forcing the process to stop. This example starts
a bundled worker that deliberately hangs; replace its command with your CLI.

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/StopHungCommandExample.java#timeout -->
```java
CommandResult result = Procwright.command(ExampleSupport.workerCommand("hang"))
        .run()
        .withCapture(CapturePolicy.bounded(64 * 1024))
        .withTimeout(Duration.ofMillis(250))
        .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(250), Duration.ofSeconds(1)))
        .execute();

if (!result.timedOut()) {
    throw new IllegalStateException("Expected the worker to time out");
}
```

[Open `StopHungCommandExample.java`](../examples/java/io/github/ulviar/procwright/examples/StopHungCommandExample.java) and
the [shared example sources](../examples.md#core).

The first duration limits command execution. The shutdown policy then allows 250 milliseconds for interruption before
using forceful termination, which gets its own one-second deadline. Inspect `CommandResult.timedOut()` to distinguish
this outcome from a normal non-zero exit.

Long-lived sessions have separate request, match, and idle timeouts. Their effect on session reuse depends on the
scenario; see [timeouts and shutdown](../reference/policies.md#timeouts-and-shutdown) and
[session failures](../reference/results-and-errors.md#decide-whether-the-session-can-be-reused).
Close each session handle when finished.

Detached or inaccessible descendants can survive process-tree cleanup. See
[timeout and close guarantees](../explanations/process-cleanup-limits.md) when a command starts other processes.
