# Replace a finite ProcessBuilder call

Move the executable and argv into `run()`. Procwright drains both output streams, applies its timeout and shutdown policy,
and returns output with exit metadata:

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/RunExample.java#run -->
```java
CommandResult result =
        Procwright.command(executable).run().withArgs(arguments).execute();
```

Set `executable` to the program path and `arguments` to its argv entries. Keep arguments separate; ordinary arguments are
not interpreted as shell commands. The defaults are a 30-second timeout and 1 MiB retained per output stream.

[Run this example and substitute your command](../getting-started.md). Inspect `result.succeeded()` and output truncation
before using the result. A normal non-zero exit is a result; launch or supervision failure throws an exception.

Use `CommandSpec` to share a working directory, base arguments, or environment across calls. See
[command configuration](../reference/command-model.md) and [run policies](../scenarios/run.md).

For a process that stays alive across requests, continue with [a worker service](wrap-cli-tool.md).
