# Follow live output

Run a command and show its logs or progress as they arrive:

```shell
./gradlew -q demoListen --console=plain
```

The bundled worker prints log lines to stdout and progress to stderr, then exits. In a terminal, the progress updates
one row. An IDE console or redirected output may display the updates differently.

The example forwards each text fragment to its matching output stream. `command` is a `CommandSpec` for the tool to run.

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/ListenExample.java#listen -->
```java
try (StreamSession stream = Procwright.command(command)
        .listen()
        .withTimeout(Duration.ofSeconds(10))
        .onOutput(chunk -> {
            var output = chunk.source() == StreamSource.STDOUT ? System.out : System.err;
            output.print(chunk.text());
            output.flush();
        })
        .open()) {
    StreamExit exit = stream.onExit().join();
    if (exit.timedOut() || exit.exitCode().orElse(-1) != 0) {
        throw new IllegalStateException("Command did not complete successfully: " + exit);
    }
}
```

[Complete example and imports](../examples/java/io/github/ulviar/procwright/examples/ListenExample.java).
To follow your own tool, pass its executable and arguments with `demoListen --args='your-tool --verbose'`, or replace
the command in the Java source. Adjust the timeout to the expected run duration.

Use `print`, not `println`: chunks can end in the middle of a line, and adding a newline would change the output.
One chunk may also contain several lines or progress updates. `flush()` makes the forwarded text available immediately.

## Progress that redraws a row

Commands often write `\r` to return the cursor to the start of the current row, then print updated text over it.
The callback above passes this character through; the terminal performs the redraw. `\r` does not erase the rest of a
longer previous row. A command can use spaces or terminal escape sequences to clear it.

Procwright does not interpret the output as percentages or reconstruct a terminal screen. Some tools disable progress
when their output is a pipe. Enable the tool's own progress option if it has one; `listen()` does not create a terminal.

## Stop and handle failures

Set `onOutput` before `open()`. Closing the session stops the process. The timeout also bounds commands that never exit.
`listen()` closes the command's stdin; choose [`interactive()`](../scenarios/interactive.md) if you need to send input.

Keep the callback short: a slow callback holds up output reading. If it throws, the session stops and `onExit()` fails.
For delivery, failure, and shutdown guarantees, see the [streaming reference](../scenarios/streaming.md).
