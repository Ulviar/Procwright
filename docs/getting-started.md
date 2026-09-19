# Run your first command

Start from the checkout root with JDK 25 on your path. If you have several JDKs, point `JAVA_HOME` at JDK 25.
Gradle downloads its wrapper and dependencies on the first run.

## Run the included demo

```shell
./gradlew -q demoRun
```

On Windows, use `.\gradlew.bat -q demoRun`. No external CLI is needed: the demo invokes the current JDK.
You should see its version information and then:

```text
succeeded=true
```

The exact Java version text depends on your JDK. The task builds Procwright and the example directly from this checkout.

## Substitute your command

Pass the executable followed by its arguments. For example, if Git is installed:

```shell
./gradlew -q demoRun --args='git --version'
```

Edit `executable` and `arguments` in the [complete example](examples/java/io/github/ulviar/procwright/examples/RunExample.java)
when embedding this call in your own code:

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/RunExample.java#run -->
```java
CommandResult result =
        Procwright.command(executable).run().withArgs(arguments).execute();
```

Keep executable and argv separate. `"git status"` is not an executable name, and Procwright does not interpret shell syntax
in ordinary arguments. [Command construction](reference/command-model.md) covers working directories, environment, and
explicit shell commands.

## Use the result

`result.stdout()` and `result.stderr()` contain captured text. The default timeout is 30 seconds, with up to 1 MiB retained
from each stream. Check `stdoutTruncated()` and `stderrTruncated()` when your task requires complete output; larger output
continues to be drained. [Output policies](scenarios/run.md) explain file and discard alternatives.

A non-zero exit is a `CommandResult`. This demo prints `succeeded=false` and makes the Gradle task fail using:

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/RunExample.java#failure -->
```java
if (!result.succeeded()) {
    throw result.toException();
}
```

A launch or supervision failure throws `CommandExecutionException` directly. See [results and errors](reference/results-and-errors.md)
when you need to distinguish failure reasons.

## Reuse configuration

| Part | Meaning |
| --- | --- |
| `command(...)` | Executable and reusable launch context |
| `run()`, `protocolSession(...)`, or another scenario | How you interact with that process |
| `with*` | A new configuration; keep the returned value |
| `execute()` / `open()` | Start work and obtain a result / owned handle |

## Take the next step

- [Use a JSON Lines worker as a service](how-to/wrap-cli-tool.md): two calls through one process, with explicit ownership.
- [Follow live output](how-to/follow-logs.md): show logs and progress with `demoListen`.
- [Send stdin or capture output in files](how-to/run-finite-command.md): adapt the command to your data.
- [Choose another process scenario](how-to/choose-process-scenario.md): line protocols, prompts, and raw I/O.
- [Use Procwright in your application](release/installation.md): Gradle and Maven dependency setup.
