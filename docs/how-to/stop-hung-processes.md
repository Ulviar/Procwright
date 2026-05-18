# Stop Hung Processes

Use `run` with explicit timeout and shutdown policies when a command can hang or leave child processes behind.

## Steps

1. Keep the workflow as `run` if the command should still be finite.
2. Set a command timeout.
3. Choose a `ShutdownPolicy` that gives the process a short graceful window before forceful termination.
4. Read the timeout outcome from `CommandResult`.

```java
logs.run(call -> call.args("logs")
        .timeout(Duration.ofSeconds(30))
        .capture(CapturePolicy.bounded(128 * 1024))
        .shutdown(ShutdownPolicy.interruptThenKill(Duration.ofSeconds(2), Duration.ofSeconds(5))));
```

Compile-tested source: `CommandServiceApiExamples.policyComposition`.

## Use this scenario because

Timeout, shutdown escalation, stream draining, and process-tree cleanup are runtime invariants. They should not be
assembled at every call site with custom watcher threads.
