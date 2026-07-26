# Automate prompts

Select the Expect branch before launch so prompt matching owns process output from the start.

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

Configure process and matching options on the same draft before calling `open()`. Closing `Expect` stops its process.

If prompts contain ANSI color or cursor-control CSI sequences, add
`interactive().expect().withAnsiControlSequenceStripping()` before `open()`.
