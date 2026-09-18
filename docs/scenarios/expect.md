# Expect automation

Choose `interactive().expect()` before launch. It returns an immutable `ExpectScenario.Draft`; each `with*` call returns a
new branch, and `open()` starts one process whose output is dedicated to prompt matching.

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

An Expect process does not expose raw stdout or stderr, so its matcher has one coherent output source. Closing `Expect`
stops that process. Match and transcript buffers are bounded independently; configure both when prompts or retained
diagnostics can be large.

Call `closeStdin()` when the command produces its final output only after input EOF; unlike `close()`, it leaves the
process and output matcher running. `withCharset(...)` configures both text input and output by default. Add
`withOutputCharset(...)` only for a command that uses a different output encoding. The physical close continues
asynchronously. If it later fails while the process is still running, `onExit()` completes exceptionally with the
original failure, which is not required to be an `ExpectException`.

A match timeout, EOF, close, or output failure throws `ExpectException` with its stable reason and bounded transcript
snapshot. A timeout while waiting for new output or the serialized matcher slot leaves `Expect` open for another match.
If a regex evaluation is abandoned before it completes, the timeout is terminal: the process stops and later matcher
calls keep the selected failure, even if the old evaluation eventually returns. Do not treat the `TIMEOUT` reason alone
as permission to retry a regex call; use a fresh handle after abandoned evaluation. Close, output failure, and EOF keep
the first selected reason when operations race. Output and input failures are terminal. EOF
reported to a matcher before normal output drain stops the process when it is still live. A matcher that materializes EOF
after normal output drain does not replace the process result.

For programs that decorate prompts with ANSI CSI sequences, enable the built-in incremental CSI stripper on the draft:

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/AnsiExpectExample.java -->
```java
/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.session.Expect;
import java.time.Duration;

public final class AnsiExpectExample {

    private AnsiExpectExample() {}

    public static void main(String[] args) {
        try (Expect expect = Procwright.command(ExampleSupport.workerCommand("ansi-expect"))
                .interactive()
                .expect()
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

See [scenario defaults](../reference/defaults.md#expect) for match timeout, transcript, match buffer, charsets, ANSI, and
redaction values.
