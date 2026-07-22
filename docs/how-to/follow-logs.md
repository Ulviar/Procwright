# Follow continuous output

Use `listen()` when output must be handled as it arrives instead of retained as a complete result.

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

Set `onOutput` before `open()`. The callback owns each delivered chunk; do not also read process stdout or stderr.
Close the `StreamSession`, or set an absolute timeout when the producer may never exit.
