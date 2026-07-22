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
