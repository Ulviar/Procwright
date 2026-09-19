# Require a terminal

Some CLIs show prompts or accept terminal control keys only when attached to a terminal. Set `TerminalPolicy.REQUIRED`
before opening that session. This example checks terminal startup with the current JDK's `java --version` command.

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/TerminalExample.java -->
```java
/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.session.Expect;
import io.github.ulviar.procwright.session.SessionExit;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class TerminalExample {

    private TerminalExample() {}

    public static void main(String[] args) {
        try (Expect expect = Procwright.command(ExampleSupport.javaExecutable())
                .interactive()
                .expect()
                .withArgs("--version")
                .withTerminal(TerminalPolicy.REQUIRED)
                .withIdleTimeout(Duration.ofSeconds(10))
                .withTimeout(Duration.ofSeconds(5))
                .open()) {
            expect.expectRegex(Pattern.compile("(?i)(java|openjdk)"));
            SessionExit exit = expect.onExit().orTimeout(5, TimeUnit.SECONDS).join();
            if (exit.exitCode().orElse(-1) != 0) {
                throw new IllegalStateException("java --version failed: " + exit.exitCode());
            }
        }
    }
}
```

[Open `TerminalExample.java`](../examples/java/io/github/ulviar/procwright/examples/TerminalExample.java) and the
[shared example sources](../examples.md#core).

Replace `ExampleSupport.javaExecutable()` and `--version` with your CLI and its arguments, then change the expected
output. `java --version` itself does not require a terminal; here it verifies that the requested transport starts.

`REQUIRED` fails before returning a session if no configured `PtyProvider` can supply a terminal. Use
`TerminalPolicy.AUTO` only when ordinary pipes are an acceptable fallback. The built-in provider supports compatible
macOS and Linux systems; Windows ConPTY support is not included. See
[terminal requirements](../scenarios/terminal.md#platform-requirements) for the required system tools.

`sendSignal(...)` sends a control byte. A PTY normally turns it into a signal for the foreground command; pipe fallback
does not. Use `REQUIRED` when the operation depends on signal semantics.
