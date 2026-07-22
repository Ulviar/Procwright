# Expect automation

`session.expect()` returns an immutable `Expect.Draft` without claiming process output. Each `with*` call returns a new
draft. Call `open()` to claim both output streams for prompt matching.

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

Getting raw stdout or stderr wrappers does not claim them, but the first effective operation on either wrapper selects raw
mode. If raw mode or another helper wins first, `Expect.Draft.open()` throws `IllegalStateException`. Do not perform raw
output operations while `Expect` is open. Closing `Expect` closes its underlying session; it does not return the streams to
raw code. Match and transcript buffers are bounded independently; configure both when prompts or retained diagnostics can
be large.

A match timeout, EOF, close, or output failure throws `ExpectException` with its stable reason and bounded transcript
snapshot. A timeout leaves `Expect` and its process open, so you can retry or wait for a different prompt. Close, output
failure, and EOF keep the first selected reason when operations race.

For programs that decorate prompts with ANSI CSI sequences, enable the built-in incremental CSI stripper on the draft:

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/AnsiExpectExample.java -->
```java
/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.session.Expect;
import io.github.ulviar.procwright.session.Session;
import java.time.Duration;

public final class AnsiExpectExample {

    private AnsiExpectExample() {}

    public static void main(String[] args) {
        try (Session session = Procwright.command(ExampleSupport.workerCommand("ansi-expect"))
                        .interactive()
                        .open();
                Expect expect = session.expect()
                        .withAnsiControlSequenceStripping()
                        .withTimeout(Duration.ofSeconds(5))
                        .open()) {
            expect.expectText("ready> ");
        }
    }
}
```

[Open `AnsiExpectExample.java`](../examples/java/io/github/ulviar/procwright/examples/AnsiExpectExample.java).

The option removes complete 7-bit ECMA-48 CSI sequences beginning with `ESC [`; it does not remove other control-sequence
families. Its state is independent for stdout and stderr. Incomplete, malformed, or overlong candidates are retained as
ordinary text, so output is not silently lost and partial state remains bounded.

See [scenario defaults](../reference/defaults.md#expect) for match timeout, transcript, match buffer, charset, ANSI, and
redaction values.
