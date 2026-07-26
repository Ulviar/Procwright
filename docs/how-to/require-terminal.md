# Require terminal capability

Set `TerminalPolicy.REQUIRED` before opening a session when the child changes behavior unless attached to a terminal.

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

`REQUIRED` fails before returning a session if no configured `PtyProvider` can supply a terminal. Use
`TerminalPolicy.AUTO` only when ordinary pipes are an acceptable fallback. The built-in Unix provider requires trusted
`script`, `stty`, `env`, and `dd` executables in `/usr/bin` or `/bin` and executable `/bin/sh`; Windows ConPTY is not
shipped in the planned `0.1.0` release.

`sendSignal(...)` sends a control byte. A PTY normally turns it into a signal for the foreground command; pipe fallback
does not. Use `REQUIRED` when the operation depends on signal semantics.
