# Run a finite command

Choose `run()` when the child should exit and you need its status and bounded output.

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

Add arguments with `withArgs`, set a finite timeout, then call `execute()`. Check `succeeded()` before consuming a result
as success. A timed-out result is never successful, even if an exit code is available.

The example retains at most 256 KiB per output stream. See the exact capture and truncation behavior in the
[run reference](../scenarios/run.md).
