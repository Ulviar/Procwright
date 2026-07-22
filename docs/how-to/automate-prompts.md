# Automate prompts

Open an interactive session, then open one `Expect` helper to own its output.

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/ExpectExample.java -->
```java
/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.session.Expect;
import io.github.ulviar.procwright.session.Session;
import java.time.Duration;

public final class ExpectExample {

    private ExpectExample() {}

    public static void main(String[] args) {
        try (Session session = Procwright.command(ExampleSupport.workerCommand("expect"))
                        .interactive()
                        .withIdleTimeout(Duration.ofSeconds(10))
                        .open();
                Expect expect =
                        session.expect().withTimeout(Duration.ofSeconds(5)).open()) {
            expect.expectText("ready> ");
            expect.sendLine("café");
            expect.expectText("ok:café");
        }
    }
}
```

[Open `ExpectExample.java`](../examples/java/io/github/ulviar/procwright/examples/ExpectExample.java) and the
[shared example sources](../examples.md#core).

Configure matching on `session.expect()` before calling `open()`. Do not read `session.stdout()` or
`session.stderr()` while `Expect` owns output. Closing `Expect` also closes the underlying session and stops its process;
output ownership is not returned to raw session code.

If prompts contain ANSI color or cursor-control CSI sequences, add
`session.expect().withAnsiControlSequenceStripping()` before `open()`.
