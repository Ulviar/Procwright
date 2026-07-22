# Command model

`Procwright.command(executable)` creates a reusable `CommandService`. Use `Procwright.command(CommandSpec)` when calls
share base argv, a working directory, or environment changes.

`CommandSpec` is immutable:

- `CommandSpec.of(executable)` creates a direct command.
- `withArg` and `withArgs` append argv entries without shell parsing.
- `withWorkingDirectory` sets the child directory.
- `withInheritedEnvironment` or `withCleanEnvironment` chooses the initial environment; `withEnvironment` then adds or
  replaces one entry.
- `CommandSpec.shell(commandLine)` explicitly delegates parsing and quoting to the operating-system shell and does not
  accept argv additions.

Scenario Drafts are also immutable. Configuration methods return a new Draft, and only `execute()` or `open()` starts a
process.

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/RunExample.java -->
```java
/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CommandResult;
import java.nio.file.Path;
import java.time.Duration;

public final class RunExample {

    private RunExample() {}

    public static void main(String[] args) {
        CommandResult result = Procwright.command(javaExecutable())
                .run()
                .withArgs("--version")
                .withCapture(CapturePolicy.bounded(256 * 1024))
                .withTimeout(Duration.ofSeconds(5))
                .execute();

        System.out.print(result.stdout());
        System.err.print(result.stderr());
        System.err.printf(
                "exit=%s, timedOut=%s, stdoutTruncated=%s, stderrTruncated=%s%n",
                result.exitCode().isPresent()
                        ? Integer.toString(result.exitCode().getAsInt())
                        : "unavailable",
                result.timedOut(),
                result.stdoutTruncated(),
                result.stderrTruncated());

        if (!result.succeeded()) {
            throw result.toException();
        }
    }

    private static String javaExecutable() {
        String name = System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", name).toString();
    }
}
```

[Open `RunExample.java`](../examples/java/io/github/ulviar/procwright/examples/RunExample.java).

Direct argv is the portable and injection-resistant default. Procwright does not normalize shell quoting or expand shell
built-ins.
