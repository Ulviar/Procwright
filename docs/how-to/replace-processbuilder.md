# Replace a finite ProcessBuilder call

For a command that exits, move the executable, argv, working directory, and environment to the `run()` Draft. Keep argv
separate; do not join it into a shell string.

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

Use `CommandSpec.of(executable).withArgs(...)` when several calls share base arguments or environment. Use
`CommandSpec.shell(commandLine)` only when shell syntax is intentional; shell quoting and portability then belong to
your application.

For a process that remains alive, choose [interactive](../scenarios/interactive.md),
[line-session](../scenarios/line-session.md), [protocol-session](../scenarios/protocol-session.md), or
[streaming](../scenarios/streaming.md) instead of manually pumping streams.
