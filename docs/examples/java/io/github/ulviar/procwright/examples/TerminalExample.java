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
