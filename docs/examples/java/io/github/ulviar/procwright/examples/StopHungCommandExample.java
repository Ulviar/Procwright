/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import java.time.Duration;

public final class StopHungCommandExample {

    private StopHungCommandExample() {}

    public static void main(String[] args) {
        CommandResult result = Procwright.command(ExampleSupport.workerCommand("hang"))
                .run()
                .withCapture(CapturePolicy.bounded(64 * 1024))
                .withTimeout(Duration.ofMillis(250))
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofMillis(250), Duration.ofSeconds(1)))
                .execute();

        if (!result.timedOut()) {
            throw new IllegalStateException("Expected the worker to time out");
        }
    }
}
