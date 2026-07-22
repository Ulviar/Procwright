/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.CommandService;
import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.session.Expect;
import io.github.ulviar.procwright.session.ExpectMatch;
import java.nio.file.Path;
import java.time.Duration;

final class ReferenceApiExamples {

    void commandDefaults() {
        Path projectDir = Path.of(".");

        CommandSpec command =
                CommandSpec.of("python").withWorkingDirectory(projectDir).withEnvironment("PYTHONUTF8", "1");

        CommandService python = Procwright.command(command);

        python.run().withArg("--version").execute();
    }

    void policyComposition() {
        CommandService logs = Procwright.command("tool");

        CommandResult result = logs.run()
                .withArgs("logs")
                .withTimeout(Duration.ofSeconds(30))
                .withCapture(CapturePolicy.bounded(128 * 1024))
                .withShutdown(ShutdownPolicy.interruptThenKill(Duration.ofSeconds(2), Duration.ofSeconds(5)))
                .execute();
        if (result.timedOut()) {
            throw result.toException();
        }
    }

    void directArgv() {
        CommandService git = Procwright.command("git");

        CommandResult result = git.run().withArgs("status", "--short").execute();

        if (!result.succeeded()) {
            throw result.toException();
        }
    }

    void explicitShell() {
        CommandService shell = Procwright.command(CommandSpec.shell("printf '%s\\n' \"$MESSAGE\""));

        shell.run().withEnvironment("MESSAGE", "hello").execute();
    }

    void expectMatchExtraction(Expect expect) {
        ExpectMatch match = expect.expectRegexMatch(java.util.regex.Pattern.compile("version (\\d+\\.\\d+)"));
        String version = match.groups().get(0);
        String beforeMatch = match.before();

        if (version.isEmpty() || beforeMatch.isEmpty()) {
            throw new IllegalStateException("unexpected empty match data");
        }
    }
}
