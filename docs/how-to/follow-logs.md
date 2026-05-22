# Follow Logs

Use `listen` for commands such as `tail -f`, `kubectl logs -f`, or local development servers where output should be
processed while the process is still alive.

## Steps

1. Start the command with `listen`.
2. Register an output listener.
3. Keep listener work bounded and fast.
4. Join `stream.onExit()` or close the session when the caller is done.

```java
try (StreamSession stream =
        tool.listen().withArgs("logs", "--follow").onOutput(chunk -> {
            if (chunk.source() == StreamSource.STDERR) {
                System.err.print(chunk.text());
            }
        }).open()) {
    stream.onExit().join();
}
```

Compile-tested source: `CommandServiceApiExamples.listenOnlyStreamingScenario`.

## Use this scenario because

`listen` drains stdout and stderr while retaining only bounded diagnostics. `run` is a better fit when the caller needs
a completed result with captured output.
