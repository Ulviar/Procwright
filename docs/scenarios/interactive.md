# Interactive sessions

`interactive()` gives you the process's raw streams through `Session`. Choose it when your application needs to read and
write the CLI's protocol directly. For prompt matching, start with [Expect](../how-to/automate-prompts.md).

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
        ExecutorService drains = Executors.newVirtualThreadPerTaskExecutor();
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

Replace `ExampleSupport.workerCommand("interactive")` with your executable or `CommandSpec`. The bundled worker reads
one line, writes the answer to stdout, and writes `processed` to stderr.

## Read both output streams

Drain stdout and stderr concurrently when the child can write to both. Waiting for exit before draining can block the
child on a full pipe. `onExit()` does not wait for your readers; join them separately, as the example does.

The example uses `readAllBytes()` because the worker's response is small and finite. For large or ongoing output, read
incrementally or copy each stream to its destination. Procwright does not bound data that your raw-stream readers retain.

## Input and shutdown

`closeStdin()` rejects later writes and starts closing the input stream. It leaves the process running so you can read a
response produced after EOF. The return does not confirm that the child has observed EOF; wait for its protocol response
when that matters. If closing cannot start, the call throws and stops the session. A later close failure can complete
`onExit()` exceptionally while the session is still running.

Closing the session requests process shutdown. Potentially blocking stream cleanup can continue asynchronously.
`withIdleTimeout(...)` limits inactivity; it is not an absolute runtime limit.

If you reuse a draft concurrently, its readiness probe, diagnostics callbacks, and custom PTY provider must be thread-safe:
separate opens can call the same instances at the same time. Alternatively, configure separate instances on each draft.

Choose `interactive().expect()`, `lineSession()`, or `protocolSession(...)` before `open()` when Procwright should consume
output. A raw session cannot switch output ownership after launch.

See [scenario defaults](../reference/defaults.md#interactive-sessions) for idle timeout, shutdown, charset, terminal,
readiness, and diagnostics values.
