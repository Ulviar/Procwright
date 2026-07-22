/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.session.Session;
import io.github.ulviar.procwright.session.SessionExit;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

public final class InteractiveExample {

    private InteractiveExample() {}

    public static void main(String[] args) throws Exception {
        ExecutorService drains = Executors.newFixedThreadPool(2);
        try (Session session = Procwright.command(ExampleSupport.workerCommand("interactive"))
                .interactive()
                .withIdleTimeout(Duration.ofSeconds(10))
                .open()) {
            Future<String> stdout =
                    drains.submit(() -> new String(session.stdout().readAllBytes(), StandardCharsets.UTF_8));
            Future<String> stderr =
                    drains.submit(() -> new String(session.stderr().readAllBytes(), StandardCharsets.UTF_8));

            session.sendLine("Привет, 世界");
            session.closeStdin();
            SessionExit exit = session.onExit().orTimeout(5, TimeUnit.SECONDS).join();

            if (exit.exitCode().orElse(-1) != 0
                    || !stdout.get(5, TimeUnit.SECONDS).contains("answer:Привет, 世界")
                    || !stderr.get(5, TimeUnit.SECONDS).contains("processed")) {
                throw new IllegalStateException("Unexpected interactive response");
            }
        } finally {
            drains.shutdownNow();
        }
    }
}
