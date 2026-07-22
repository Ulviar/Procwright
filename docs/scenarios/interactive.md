# Interactive sessions

`interactive()` opens a process whose stdin, stdout, stderr, exit future, and terminal controls are exposed through
`Session`.

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/InteractiveExample.java -->
```java
/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.session.Session;
import io.github.ulviar.procwright.session.SessionExit;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class InteractiveExample {

    private InteractiveExample() {}

    public static void main(String[] args) throws Exception {
        ExecutorService drains = Executors.newFixedThreadPool(2);
        try (Session session = Procwright.command(ExampleSupport.workerCommand("interactive"))
                .interactive()
                .withIdleTimeout(Duration.ofSeconds(10))
                .open()) {
            Future<String> stdout =
                    drains.submit(() -> new String(session.stdout().readAllBytes(), StandardCharsets.UTF_8));
            Future<String> stderr =
                    drains.submit(() -> new String(session.stderr().readAllBytes(), StandardCharsets.UTF_8));

            session.sendLine("Привет, 世界");
            session.closeStdin();
            SessionExit exit = session.onExit().orTimeout(5, TimeUnit.SECONDS).join();

            if (exit.exitCode().orElse(-1) != 0
                    || !stdout.get(5, TimeUnit.SECONDS).contains("answer:Привет, 世界")
                    || !stderr.get(5, TimeUnit.SECONDS).contains("processed")) {
                throw new IllegalStateException("Unexpected interactive response");
            }
        } finally {
            drains.shutdownNow();
        }
    }
}
```

[Open `InteractiveExample.java`](../examples/java/io/github/ulviar/procwright/examples/InteractiveExample.java) and the
[shared example sources](../examples.md#core).

The caller owns both output streams and must drain them concurrently when the child can write to both. Waiting for exit
before draining can deadlock a child on a full pipe.

`open()` starts the process. Close the returned session to stop it and release streams. `withIdleTimeout` measures
inactivity according to the session contract; it is not an absolute runtime limit.

The Draft retains its readiness probe, diagnostics recipients, and optional PTY provider. Each open waits for its own
readiness call, but concurrent opens can invoke the same retained instances concurrently. Make shared instances
thread-safe or branch the Draft with separate instances.

Opening `Expect` transfers output ownership to that helper. Do not mix raw reads with `Expect`, line-session, or protocol
decoding.

See [scenario defaults](../reference/defaults.md#interactive-sessions) for idle timeout, shutdown, charset, terminal,
readiness, and diagnostics values.
