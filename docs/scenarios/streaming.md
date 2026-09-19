# Streaming

`listen()` delivers stdout and stderr while the process runs, without collecting a complete result in memory.
Start with [Follow live output](../how-to/follow-logs.md) for a runnable log and progress example.

## Output delivery

Register `onOutput` before `open()`. Each `StreamChunk` contains a source (`STDOUT` or `STDERR`) and decoded text.
Chunks are fragments, not lines or individual writes: a line or progress update can span several callbacks. Procwright
preserves control characters such as `\r`; it does not interpret terminal escape sequences.

Within one session, callbacks are synchronous and serialized across stdout and stderr. A slow callback slows output
reading instead of creating an unbounded queue. Delivery preserves each stream's order; do not rely on an ordering
between stdout and stderr.

A Draft retains its listener. Concurrent opens can call that same listener from different sessions at once, so either
make it thread-safe or give each Draft branch its own listener.

## Completion and shutdown

`onExit()` returns a future with a `StreamExit`. Inspect `exitCode()` for the process status and `timedOut()` to detect
deadline shutdown. A nonzero exit code is a result, not an exception.

On natural completion, Procwright drains stdout and stderr. When `onExit()` completes, all callbacks have returned and
no later callback can begin. A descendant that inherits an output pipe can keep it open after the root process exits.
Set `withTimeout(...)` to bound the wait for both process exit and remaining output.

Closing the session stops the process. Close and timeout reject new output deliveries, but a callback admitted just
before stopping may still start or finish after `onExit()` completes. Shutdown does not wait indefinitely for that
callback.

`withTimeout(...)` sets an absolute runtime limit; zero disables it. The [defaults reference](../reference/defaults.md#streaming)
lists the timeout, shutdown, charset, and diagnostic settings.

## Failures and input

`open()` reports launch and construction failures. Later output I/O and ordinary callback failures stop the session
and fail `onExit()` with a `StreamException`. A fatal `Error` fails the future with the same `Error` instance.

`listen()` closes stdin when the process starts. Choose [`interactive()`](interactive.md) when you need to write input.
