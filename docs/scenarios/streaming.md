# Streaming

`listen()` delivers stdout and stderr chunks while the process runs without retaining complete output.

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/ListenExample.java -->
```java
/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.session.StreamExit;
import io.github.ulviar.procwright.session.StreamSession;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class ListenExample {

    private ListenExample() {}

    public static void main(String[] args) {
        AtomicInteger chunks = new AtomicInteger();
        try (StreamSession stream = Procwright.command(ExampleSupport.workerCommand("listen"))
                .listen()
                .withTimeout(Duration.ofSeconds(2))
                .onOutput(chunk -> chunks.incrementAndGet())
                .open()) {
            StreamExit exit = stream.onExit().orTimeout(5, TimeUnit.SECONDS).join();
            if (!exit.timedOut() || chunks.get() == 0) {
                throw new IllegalStateException("Expected bounded log streaming");
            }
        }
    }
}
```

[Open `ListenExample.java`](../examples/java/io/github/ulviar/procwright/examples/ListenExample.java) and the
[shared example sources](../examples.md#core).

Register `onOutput` before `open()`. The listener is the only output owner. Listener `RuntimeException`s, output I/O
failures, and other ordinary callback failures terminate the scenario and complete `onExit()` exceptionally with
`StreamException`. A fatal `Error` is not wrapped: `onExit()` fails with the same `Error` instance. `open()` reports only
launch and construction failures.

Within one stream session, stdout and stderr listener calls are synchronous and serialized. Concurrent opens of one Draft
can invoke the same retained listener instance from different sessions, so a shared listener must be thread-safe. Use
separate Draft branches with separate listener instances when it is not. Diagnostic recipients follow their asynchronous
delivery contract and can also overlap across sessions.

After natural process exit completes `onExit()`, all listener calls have returned and no later call can begin. This
preserves output that Procwright has already read from the process. Explicit `close()` and the scenario timeout admit no
further deliveries, but a delivery admitted immediately before stopping may invoke or remain inside the listener after
`onExit()` completes. Application shutdown therefore is not held indefinitely by that callback.

Natural completion also waits for stdout and stderr EOF. A descendant that inherits either pipe can keep it open after
the root process exits. Use `withTimeout(...)` when that topology is possible; the same absolute timeout bounds the
remaining output drain and then applies the configured shutdown policy.

`listen()` closes stdin when the process starts. Use [`interactive()`](interactive.md) when the caller needs to write
stdin.

`withTimeout` sets an absolute runtime limit; zero disables it. `StreamExit.timedOut()` distinguishes deadline shutdown
from a normal exit. Closing the session stops its process.

See [scenario defaults](../reference/defaults.md#streaming) for timeout, shutdown, charset, retained diagnostics, and
listener values.
