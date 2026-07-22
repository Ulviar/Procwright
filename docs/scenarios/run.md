# Run

`run()` executes a finite command and returns `CommandResult`.

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

The Draft is immutable and reusable. `withArg` and `withArgs` append scenario arguments after any base arguments in the
`CommandSpec`. Only `execute()` starts a process.

Procwright drains stdout and stderr, applies the configured timeout and shutdown policy, then returns captured bytes and
decoded text. In the example, `CapturePolicy.bounded(256 * 1024)` retains the first 262,144 bytes separately from each
stream while continuing to drain later output. `stdoutTruncated()` and `stderrTruncated()` identify streams whose later
bytes were discarded. Redirected or discarded streams produce empty captured values.

`CommandResult.succeeded()` requires a zero exit code and no timeout. Launch and supervision failures throw
`CommandExecutionException`; a normal non-zero exit remains a result until the caller converts it with `toException()`.

Handle launch failure from the exception reason, not from a presumed result:

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/RunFailureExample.java -->
```java
/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import static io.github.ulviar.procwright.command.CommandExecutionException.Reason.LAUNCH_FAILED;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CommandExecutionException;
import io.github.ulviar.procwright.command.CommandResult;

public final class RunFailureExample {

    private RunFailureExample() {}

    public static CommandResult execute(String executable) {
        try {
            return Procwright.command(executable).run().execute();
        } catch (CommandExecutionException failure) {
            if (failure.reason() == LAUNCH_FAILED) {
                throw new IllegalStateException("Command could not be launched", failure);
            }
            throw failure;
        }
    }
}
```

[Open `RunFailureExample.java`](../examples/java/io/github/ulviar/procwright/examples/RunFailureExample.java).

`LAUNCH_FAILED` occurs before a `CommandResult` exists. The example maps that case and preserves every other
`CommandExecutionException`; it does not assume that `failure.result()` is present.

See [scenario defaults](../reference/defaults.md#run) before relying on the initial timeout, capture limit, decoding, or
shutdown behavior.
