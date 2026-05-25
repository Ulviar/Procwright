package io.github.ulviar.icli.examples;

import io.github.ulviar.icli.CommandService;
import io.github.ulviar.icli.Icli;
import io.github.ulviar.icli.command.CapturePolicy;
import io.github.ulviar.icli.command.CommandResult;
import io.github.ulviar.icli.command.CommandSpec;
import io.github.ulviar.icli.command.ShutdownPolicy;
import java.nio.file.Path;
import java.time.Duration;

final class ReferenceApiExamples {

    void commandDefaults() {
        Path projectDir = Path.of(".");

        CommandSpec command = CommandSpec.builder("python")
                .workingDirectory(projectDir)
                .putEnvironment("PYTHONUTF8", "1")
                .build();

        CommandService python = Icli.command(command);

        python.run().execute("--version");
    }

    void policyComposition() {
        CommandService logs = Icli.command("tool");

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
        CommandService git = Icli.command("git");

        CommandResult result = git.run().execute("status", "--short");

        if (!result.succeeded()) {
            throw result.toException();
        }
    }

    void explicitShell() {
        CommandService shell = Icli.shellCommand("printf '%s\\n' \"$MESSAGE\"");

        shell.run().withEnvironment("MESSAGE", "hello").execute();
    }
}
