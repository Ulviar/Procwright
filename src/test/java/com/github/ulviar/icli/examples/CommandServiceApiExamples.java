package com.github.ulviar.icli.examples;

import com.github.ulviar.icli.CapturePolicy;
import com.github.ulviar.icli.CommandResult;
import com.github.ulviar.icli.CommandService;
import com.github.ulviar.icli.CommandSpec;
import com.github.ulviar.icli.Expect;
import com.github.ulviar.icli.ExpectOptions;
import com.github.ulviar.icli.LineResponse;
import com.github.ulviar.icli.LineSession;
import com.github.ulviar.icli.LineSessionOptions;
import com.github.ulviar.icli.RunOptions;
import com.github.ulviar.icli.Session;
import com.github.ulviar.icli.SessionOptions;
import com.github.ulviar.icli.ShutdownPolicy;
import com.github.ulviar.icli.StreamSession;
import com.github.ulviar.icli.StreamSource;
import com.github.ulviar.icli.TerminalPolicy;
import com.github.ulviar.icli.TerminalSignal;
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

    void lineSessionScenario() {
        CommandService repl = new CommandService(
                CommandSpec.of("tool"),
                RunOptions.defaults(),
                SessionOptions.defaults(),
                LineSessionOptions.defaults().withRequestTimeout(Duration.ofSeconds(2)));

        try (LineSession session = repl.lineSession(call -> call.args("repl"))) {
            LineResponse response = session.request("status");
            if (response.text().isBlank()) {
                throw new IllegalStateException("empty response");
            }
        }
    }

    void expectScenario() {
        CommandService repl = CommandService.forCommand("tool");

        try (Session session = repl.interactive(call -> call.args("repl"));
                Expect expect = session.expect(ExpectOptions.defaults().withTimeout(Duration.ofSeconds(2)))) {
            expect.expectText("ready> ");
            expect.sendLine("status");
            expect.expectRegex(java.util.regex.Pattern.compile("ok|ready"));
        }
    }

    void terminalRequiredSessionScenario() {
        CommandService shell = CommandService.forCommand("sh");

        try (Session session = shell.interactive(call -> call.terminal(TerminalPolicy.REQUIRED))) {
            session.sendSignal(TerminalSignal.INTERRUPT);
        }
    }

    void listenOnlyStreamingScenario() {
        CommandService tool = CommandService.forCommand("tool");

        try (StreamSession stream =
                tool.listen(call -> call.args("logs", "--follow").onOutput(chunk -> {
                    if (chunk.source() == StreamSource.STDERR) {
                        System.err.print(chunk.text());
                    }
                }))) {
            stream.onExit().join();
        }
    }
}
