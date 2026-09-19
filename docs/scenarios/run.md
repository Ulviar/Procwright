# Run

`run()` executes a finite command and returns `CommandResult`. Here, `executable` and `arguments` are the program and
separate argv entries; [Getting started](../getting-started.md) runs the complete example.

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/RunExample.java#run -->
```java
CommandResult result =
        Procwright.command(executable).run().withArgs(arguments).execute();
```

[Open `RunExample.java`](../examples/java/io/github/ulviar/procwright/examples/RunExample.java).

The Draft is immutable and reusable. `withArg` and `withArgs` append scenario arguments after any base arguments in the
`CommandSpec`. Only `execute()` starts a process.

Procwright drains stdout and stderr, applies the configured timeout and shutdown policy, then returns captured bytes and
decoded text. The default capture retains the first 1 MiB separately from each stream while continuing to drain later
output. Use `withCapture(CapturePolicy.bounded(bytes))` to change that budget. `stdoutTruncated()` and `stderrTruncated()`
identify streams whose later bytes were discarded. Redirected or discarded streams produce empty captured values.

The timeout is one deadline for stdin writing, process waiting, and output drain. A child process that keeps an
inherited output pipe open can therefore make the result timed out after the root process has already exited. Required
process-tree cleanup starts after that outcome is selected and remains bounded by the shutdown policy.

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
