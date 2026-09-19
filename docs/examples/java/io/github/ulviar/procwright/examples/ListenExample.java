/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.session.StreamExit;
import io.github.ulviar.procwright.session.StreamSession;
import io.github.ulviar.procwright.session.StreamSource;
import java.time.Duration;
import java.util.Arrays;

public final class ListenExample {

    private ListenExample() {}

    public static void main(String[] args) {
        CommandSpec command = args.length == 0
                ? ExampleSupport.workerCommand("listen")
                : CommandSpec.of(args[0]).withArgs(Arrays.copyOfRange(args, 1, args.length));

        // docs:start listen
        try (StreamSession stream = Procwright.command(command)
                .listen()
                .withTimeout(Duration.ofSeconds(10))
                .onOutput(chunk -> {
                    var output = chunk.source() == StreamSource.STDOUT ? System.out : System.err;
                    output.print(chunk.text());
                    output.flush();
                })
                .open()) {
            StreamExit exit = stream.onExit().join();
            if (exit.timedOut() || exit.exitCode().orElse(-1) != 0) {
                throw new IllegalStateException("Command did not complete successfully: " + exit);
            }
        }
        // docs:end listen
    }
}
