# Automate prompts

Wait for a prompt, send a reply, then wait for the command's response with `interactive().expect()`.
The example uses a bundled worker that prints `ready> ` and echoes each reply with an `ok:` prefix.

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/ExpectExample.java -->
```java
/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.session.Expect;
import java.time.Duration;

public final class ExpectExample {

    private ExpectExample() {}

    public static void main(String[] args) {
        try (Expect expect = Procwright.command(ExampleSupport.workerCommand("expect"))
                .interactive()
                .expect()
                .withIdleTimeout(Duration.ofSeconds(10))
                .withTimeout(Duration.ofSeconds(5))
                .open()) {
            expect.expectText("ready> ");
            expect.sendLine("café");
            expect.expectText("ok:café");
        }
    }
}
```

[Open `ExpectExample.java`](../examples/java/io/github/ulviar/procwright/examples/ExpectExample.java) and the
[shared example sources](../examples.md#core).

For your CLI, replace `ExampleSupport.workerCommand("expect")` with its executable or `CommandSpec`, then change the
expected prompts and replies. Configure arguments and timeouts before `open()`. Closing `Expect` stops its process.

Matching reads stdout; stderr is drained into the diagnostic transcript. If the CLI requires a terminal to show its
prompts, [require a terminal](require-terminal.md).

For ANSI-decorated prompts, add `withAnsiControlSequenceStripping()` before `open()`; see the
[ANSI example and matching limits](../scenarios/expect.md#ansi-decorated-prompts). This removes CSI sequences from the
text; it does not reconstruct a terminal screen.
