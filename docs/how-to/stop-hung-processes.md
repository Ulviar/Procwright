# Stop Hung Processes

Use `run` with explicit timeout and shutdown policies when a command can hang or leave child processes behind.

## Steps

1. Keep the workflow as `run` if the command should still be finite.
2. Set a command timeout.
3. Choose a `ShutdownPolicy` that gives the process a short graceful window before forceful termination.
4. Read the timeout outcome from `CommandResult`.

```java
CommandService logs = Procwright.command("tool");

CommandResult result = logs.run()
        .withArgs("logs")
        .withTimeout(Duration.ofSeconds(30))
        .withCapture(CapturePolicy.bounded(128 * 1024))
        .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofSeconds(2), Duration.ofSeconds(5)))
        .execute();
if (result.timedOut()) {
    throw result.toException();
}
```

## Use this scenario because

Timeout, shutdown escalation, stream draining, and process-tree cleanup are runtime invariants. They should not be
assembled at every call site with custom watcher threads.

Cleanup uses JDK `ProcessHandle` descendant tracking. It covers the process tree visible to the JVM, but it is not an OS
sandbox: detached children, inaccessible process handles, platform limits, or commands that deliberately escape their
parent tree may need caller-side containment outside Procwright.
