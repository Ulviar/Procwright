# Terminal capability

Session-family Drafts accept `TerminalPolicy` when a child needs terminal behavior instead of ordinary pipes.

<!-- procwright-example: examples/java/io/github/ulviar/procwright/examples/TerminalExample.java -->
```java
/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.session.Expect;
import io.github.ulviar.procwright.session.Session;
import io.github.ulviar.procwright.session.SessionExit;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

public final class TerminalExample {

    private TerminalExample() {}

    public static void main(String[] args) {
        try (Session session = Procwright.command(ExampleSupport.javaExecutable())
                        .interactive()
                        .withArgs("--version")
                        .withTerminal(TerminalPolicy.REQUIRED)
                        .withIdleTimeout(Duration.ofSeconds(10))
                        .open();
                Expect expect =
                        session.expect().withTimeout(Duration.ofSeconds(5)).open()) {
            expect.expectRegex(Pattern.compile("(?i)(java|openjdk)"));
            SessionExit exit = session.onExit().orTimeout(5, TimeUnit.SECONDS).join();
            if (exit.exitCode().orElse(-1) != 0) {
                throw new IllegalStateException("Terminal command did not exit cleanly");
            }
        }
    }
}
```

[Open `TerminalExample.java`](../examples/java/io/github/ulviar/procwright/examples/TerminalExample.java) and the
[shared example sources](../examples.md#core).

- `DISABLED` uses ordinary process pipes.
- `AUTO` requests a terminal but permits pipe fallback.
- `REQUIRED` fails if the configured `PtyProvider` cannot create one.

The built-in Unix provider requires trusted `script`, `stty`, `env`, and `dd` executables in `/usr/bin` or `/bin` and
executable `/bin/sh`; it does not use `PATH` to find transport helpers. It enables only an exact BSD or util-linux
invocation that passes a bounded terminal, direct-`env`, and exit-code probe. Child argv and environment remain isolated
from that wrapper and travel in a bounded post-`READY` terminal-input frame, not a temporary file. See
[Platforms and terminal support](../reference/platforms-and-pty.md) for platform requirements and the fail-closed
restriction on executable tokens containing `=`. Windows ConPTY is not shipped in planned `0.1.0`; a required terminal
must fail explicitly rather than silently changing transport.

The default session policy is `DISABLED`; selecting `AUTO` or `REQUIRED` activates the built-in provider. See
[scenario defaults](../reference/defaults.md#interactive-sessions) for the initial policy, provider, and terminal size.
