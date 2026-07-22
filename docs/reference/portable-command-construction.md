# Portable command construction

Pass the executable and each argument separately. The JDK process API then preserves argument boundaries without a shell.

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

Resolve executable paths in the application when PATH contents are not trusted. Do not pass untrusted values through
`CommandSpec.shell`; shell escaping is platform-specific and remains the caller's responsibility.

Use `CommandSpec.of(executable).withArgs(...)` for reusable base argv. Scenario-level `withArgs(...)` appends arguments
for one workflow branch.
