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
