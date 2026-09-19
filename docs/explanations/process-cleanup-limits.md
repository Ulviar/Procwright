# What timeout and close guarantee

Procwright attempts to stop the processes it owns using the configured shutdown policy. It also tracks descendants
through the JDK's `ProcessHandle` API. This handles ordinary command timeouts, closed sessions, failed workers, and
listener failures, but it does not provide operating-system containment.

## Allow time for shutdown

An operation timeout selects the failed outcome and starts cleanup. Graceful shutdown and forceful termination have
separate deadlines, so returning from a timed-out operation can take longer than its operation timeout.

Closing a handle does not wait indefinitely for a blocked stream close or application callback. Those operations may
finish later. For lifecycle completion, observe the handle's `onExit()` or the pool's `closeAsync()`;
see [lifecycle futures](../reference/policies.md#lifecycle-futures).

## A child can keep output open

A command can exit while a descendant still holds its stdout or stderr pipe. Until that pipe closes, Procwright cannot
know that it has received all output.

- `run()` includes output draining in its absolute timeout. It can therefore return a timed-out result even if the main
  process exited normally.
- `listen()` keeps applying its configured absolute timeout during output draining. Its default timeout is disabled;
  set `withTimeout(...)` if the wait must be limited.
- Line, protocol, and Expect sessions wait for output draining on natural exit. Their request, match, and idle timeouts
  do not bound a later wait for `onExit()` after the main process has exited.

For the last case, keep the handle in try-with-resources and use `onExit().get(timeout, TimeUnit.SECONDS)` if you need a
bounded wait. A timeout on the future does not close the handle; leaving the resource scope does.

## Some descendants can survive

Procwright retains and attempts to stop descendants it observed while their parent was alive, including descendants
that are later reparented. Process-tree observations are not atomic: a process that starts and fully detaches between
observations may never be seen. Platform permissions can also prevent access to a descendant.

Use an OS sandbox, container, job object, or service manager when processes must not escape their lifetime or resource
limits. Procwright cleanup alone cannot enforce that boundary.

## Application code must cooperate

Request adapters, response decoders, readiness probes, and pool hooks should return promptly and respond to interruption.
A deadline can release their caller, but Java cannot forcibly stop a callback that ignores interruption. The affected
session or worker is then closed instead of accepting more work.

A streaming listener runs synchronously and slows the child through pipe backpressure. It can outlive explicit close
or timeout if it does not return. Keep listeners short; use your own bounded queue for expensive processing.

Custom `PtyProvider` implementations are trusted extensions. Their process and metadata methods must respect the
[provider timing contract](../reference/platforms-and-pty.md#custom-providers). A blocked custom implementation can
exceed the configured deadline.
