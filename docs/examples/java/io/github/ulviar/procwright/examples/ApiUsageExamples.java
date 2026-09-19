/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.session.Expect;
import io.github.ulviar.procwright.session.ProtocolAdapter;
import io.github.ulviar.procwright.session.ProtocolReaders;
import io.github.ulviar.procwright.session.ProtocolWriter;
import java.time.Duration;

public final class ApiUsageExamples {
    private ApiUsageExamples() {}

    // @start region="run"
    public static String runCommand(String executable, String... arguments) {
        CommandResult result = Procwright.command(executable)
                .run()
                .withArgs(arguments)
                .withTimeout(Duration.ofSeconds(10))
                .execute();
        if (!result.succeeded()) {
            throw result.toException();
        }
        return result.stdout();
    }
    // @end region="run"

    // @start region="adapter"
    public static ProtocolAdapter<String, String> lineAdapter() {
        return new ProtocolAdapter<>() {
            @Override
            public void writeRequest(String request, ProtocolWriter writer) {
                writer.writeLine(request);
                writer.flush();
            }

            @Override
            public String readResponse(ProtocolReaders readers) {
                return readers.stdout().readLine(4096);
            }
        };
    }
    // @end region="adapter"

    // @start region="expect"
    public static void answerPrompt(CommandSpec command, String reply) {
        try (Expect expect = Procwright.command(command)
                .interactive()
                .expect()
                .withTimeout(Duration.ofSeconds(5))
                .open()) {
            expect.expectText("ready> ");
            expect.sendLine(reply);
            expect.expectText("ok:" + reply);
        }
    }
    // @end region="expect"
}
