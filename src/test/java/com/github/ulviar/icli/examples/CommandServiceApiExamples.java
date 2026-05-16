package com.github.ulviar.icli.examples;

import com.github.ulviar.icli.CapturePolicy;
import com.github.ulviar.icli.CommandResult;
import com.github.ulviar.icli.CommandService;
import com.github.ulviar.icli.CommandSpec;
import com.github.ulviar.icli.RunOptions;
import com.github.ulviar.icli.Session;
import com.github.ulviar.icli.SessionOptions;
import com.github.ulviar.icli.ShutdownPolicy;
import java.nio.file.Path;
import java.time.Duration;

final class CommandServiceApiExamples {

    void oneShotScenario() {
        CommandService git = CommandService.forCommand("git");

        CommandResult result = git.run(call -> call.args("status", "--short"));

        if (!result.succeeded()) {
            throw result.toException();
        }
    }

    void explicitCommandConfiguration(Path projectDir) {
        CommandSpec command = CommandSpec.builder("python")
                .workingDirectory(projectDir)
                .putEnvironment("PYTHONUTF8", "1")
                .build();

        CommandService python = new CommandService(command, RunOptions.defaults());

        python.run(call -> call.args("--version"));
    }

    void policyComposition() {
        CommandService logs = CommandService.forCommand("tool");

        logs.run(call -> call.args("logs")
                .capture(CapturePolicy.bounded(128 * 1024))
                .shutdown(ShutdownPolicy.interruptThenKill(Duration.ofSeconds(2), Duration.ofSeconds(5))));
    }

    void interactiveScenario() {
        CommandService python = new CommandService(
                CommandSpec.of("python"),
                RunOptions.defaults(),
                SessionOptions.defaults().withIdleTimeout(Duration.ofMinutes(5)));

        try (Session session = python.interactive(call -> call.args("-i"))) {
            session.sendLine("print(6 * 7)");
        }
    }
}
