# Streaming

Use `listen` when the caller needs output chunks as they arrive and should not retain the entire output in memory.

The scenario covers:

- parallel stdout/stderr draining;
- synchronous listener backpressure;
- bounded diagnostics;
- default stdin close;
- timeout handling;
- listener-failure propagation through the stream exit future.

## Example

```java
CommandService tool = Icli.command("tool");

try (StreamSession stream = tool.listen()
        .withArgs("logs", "--follow")
        .onOutput(chunk -> {
            if (chunk.source() == StreamSource.STDERR) {
                System.err.print(chunk.text());
            } else {
                System.out.print(chunk.text());
            }
        })
        .open()) {
    stream.onExit().join();
}
```

More examples: [Examples](../examples.md#core-examples).

## Backpressure

Listener callbacks are synchronous for a stream session. A slow listener applies process-pipe backpressure instead of
creating an unbounded in-memory queue. Move heavy work into an application-owned bounded queue or executor.

## Failure model

`StreamSession.onExit()` completes with `StreamExit` on normal completion and may complete exceptionally with
`StreamException` when listener processing or stream supervision fails. The exception includes bounded diagnostics.
