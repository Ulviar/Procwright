# Diagnostics

Attach `withDiagnosticListener(...)` before `execute()` or `open()` to observe process events. Use `event.runId()` to
correlate one command or session, including the launches of individual pool workers. A transcript sink records snapshots
of these events; it is separate from the output transcripts attached to session errors.

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/DiagnosticsExample.java#observe -->
```java
CountDownLatch processExited = new CountDownLatch(1);
var run = Procwright.command(ExampleSupport.workerCommand("finite"))
        .run()
        .withDiagnosticListener(event -> {
            if (event.type() == DiagnosticEventType.PROCESS_EXITED) {
                processExited.countDown();
            }
        });

run.withTimeout(Duration.ofSeconds(5)).execute();
if (!processExited.await(5, TimeUnit.SECONDS)) {
    throw new IllegalStateException("PROCESS_EXITED diagnostic was not delivered");
}
```

[Open `DiagnosticsExample.java`](../examples/java/io/github/ulviar/procwright/examples/DiagnosticsExample.java) and the
[shared example sources](../examples.md#core).

Diagnostic delivery is asynchronous and bounded. For one command or session lifecycle, calls to each listener or sink are
serialized in submission order. Listener and sink delivery use independent queues and may overlap. Separate executions,
sessions, and pool workers also use independent queues. Reusing a Draft therefore lets the same supplied recipient run
concurrently; make it thread-safe or use separate Draft branches with separate recipients.

Delivery is best-effort: a slow recipient can lose pending events, and listener failure does not change the command's
outcome. The example waits briefly for its event so that the demo does not exit before asynchronous delivery. Use command
results and lifecycle futures for application control; do not rely on a diagnostic event being delivered.

Built-in events omit argument values, environment values, and raw process I/O. Command metadata still includes the
executable, working directory, and environment variable names. Scenario transcripts are different: they can contain
request data and process output. Expect redacts send/match action values by default, but that is not a general secret
filter for everything the child prints. Review transcripts and exception details before exporting them.
