package com.github.ulviar.icli.examples;

import com.github.ulviar.icli.CommandService;
import com.github.ulviar.icli.command.CapturePolicy;
import com.github.ulviar.icli.command.CommandResult;
import com.github.ulviar.icli.command.CommandSpec;
import com.github.ulviar.icli.command.RunOptions;
import com.github.ulviar.icli.command.ShutdownPolicy;
import com.github.ulviar.icli.diagnostics.DiagnosticsOptions;
import com.github.ulviar.icli.preset.ScenarioPresets;
import com.github.ulviar.icli.session.Expect;
import com.github.ulviar.icli.session.ExpectOptions;
import com.github.ulviar.icli.session.LineResponse;
import com.github.ulviar.icli.session.LineSession;
import com.github.ulviar.icli.session.LineSessionOptions;
import com.github.ulviar.icli.session.PooledLineSession;
import com.github.ulviar.icli.session.PooledLineSessionMetrics;
import com.github.ulviar.icli.session.Session;
import com.github.ulviar.icli.session.SessionOptions;
import com.github.ulviar.icli.session.StreamSession;
import com.github.ulviar.icli.session.StreamSource;
import com.github.ulviar.icli.terminal.TerminalPolicy;
import com.github.ulviar.icli.terminal.TerminalSignal;
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
                .timeout(Duration.ofSeconds(30))
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

    void diagnosticsScenario() {
        CommandService tool = CommandService.forCommand("tool")
                .withDiagnostics(DiagnosticsOptions.defaults().withListener(event -> {
                    if (event.attributes().containsKey("exitCode")) {
                        System.out.println(
                                event.type() + ":" + event.attributes().get("exitCode"));
                    }
                }));

        tool.run(call -> call.args("--version"));
    }

    void pooledLineSessionScenario() {
        CommandService tool = CommandService.forCommand("tool");

        try (PooledLineSession pool = tool.pooled(call -> call.args("repl")
                .maxSize(4)
                .warmupSize(1)
                .maxRequestsPerWorker(100)
                .reset(worker -> worker.request("reset")))) {
            LineResponse response = pool.request("status", Duration.ofSeconds(2));
            PooledLineSessionMetrics metrics = pool.metrics();
            if (response.text().isBlank() || metrics.size() > 4) {
                throw new IllegalStateException("unexpected pooled response");
            }
        }
    }

    void scenarioPresetComposition() {
        CommandService tool = CommandService.forCommand("tool");

        tool.run(call -> {
            call.args("env");
            ScenarioPresets.environmentDiagnostics(Duration.ofSeconds(2), 16 * 1024)
                    .accept(call);
        });

        try (StreamSession stream = tool.listen(call -> {
            call.args("logs", "--follow").onOutput(chunk -> System.out.print(chunk.text()));
            ScenarioPresets.logFollowing(Duration.ZERO).accept(call);
        })) {
            stream.close();
        }
    }
}
