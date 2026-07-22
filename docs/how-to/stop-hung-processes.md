# Stop a hung command

Set a timeout on the scenario Draft and choose a shutdown policy before the terminal call.

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/StopHungCommandExample.java -->
```java
/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import java.time.Duration;

public final class StopHungCommandExample {

    private StopHungCommandExample() {}

    public static void main(String[] args) {
        CommandResult result = Procwright.command(ExampleSupport.workerCommand("hang"))
                .run()
                .withCapture(CapturePolicy.bounded(64 * 1024))
                .withTimeout(Duration.ofMillis(250))
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(250), Duration.ofSeconds(1)))
                .execute();

        if (!result.timedOut()) {
            throw new IllegalStateException("Expected the worker to time out");
        }
    }
}
```

[Open `StopHungCommandExample.java`](../examples/java/io/github/ulviar/procwright/examples/StopHungCommandExample.java) and
the [shared example sources](../examples.md#core).

The first duration limits command execution. The shutdown policy then allows 250 milliseconds for interruption before
using forceful termination, which gets its own one-second deadline. Inspect `CommandResult.timedOut()` to distinguish
this outcome from a normal non-zero exit.

For sessions, an admitted operation timeout or close shuts down the owned process. A line request that times out before
stdin handoff, or a protocol request that times out while waiting for its serialized slot, leaves its direct session open
because it cannot have changed wire state. Cleanup is not an OS sandbox; detached descendants can outlive the observed
process tree. See
[process cleanup limits](../explanations/process-cleanup-limits.md).
