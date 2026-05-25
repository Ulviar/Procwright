package io.github.ulviar.icli.examples;

import io.github.ulviar.icli.CommandService;
import io.github.ulviar.icli.Icli;
import io.github.ulviar.icli.command.CapturePolicy;
import io.github.ulviar.icli.command.CommandResult;
import io.github.ulviar.icli.command.CommandSpec;
import io.github.ulviar.icli.command.ShutdownPolicy;
import io.github.ulviar.icli.diagnostics.DiagnosticsOptions;
import io.github.ulviar.icli.preset.ScenarioPresets;
import io.github.ulviar.icli.session.Expect;
import io.github.ulviar.icli.session.ExpectOptions;
import io.github.ulviar.icli.session.LineResponse;
import io.github.ulviar.icli.session.LineSession;
import io.github.ulviar.icli.session.PooledLineSession;
import io.github.ulviar.icli.session.PooledLineSessionMetrics;
import io.github.ulviar.icli.session.PooledProtocolSession;
import io.github.ulviar.icli.session.PooledProtocolSessionMetrics;
import io.github.ulviar.icli.session.ProtocolAdapter;
import io.github.ulviar.icli.session.ProtocolReader;
import io.github.ulviar.icli.session.ProtocolReaders;
import io.github.ulviar.icli.session.ProtocolSession;
import io.github.ulviar.icli.session.ProtocolWriter;
import io.github.ulviar.icli.session.Session;
import io.github.ulviar.icli.session.SessionExit;
import io.github.ulviar.icli.session.SessionOptions;
import io.github.ulviar.icli.session.StreamSession;
import io.github.ulviar.icli.session.StreamSource;
import io.github.ulviar.icli.terminal.TerminalPolicy;
import io.github.ulviar.icli.terminal.TerminalSignal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;

final class CommandServiceApiExamples {

    void oneShotScenario() {
        CommandService git = Icli.command("git");

        CommandResult result = git.run().execute("status", "--short");

        if (!result.succeeded()) {
            throw result.toException();
        }
    }

    void explicitCommandConfiguration() {
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

    void interactiveScenario() {
        CommandService python = Icli.command(CommandSpec.of("python"))
                .withSessionOptions(SessionOptions.defaults().withIdleTimeout(Duration.ofMinutes(5)));

        try (Session session = python.interactive().withArgs("-i").open()) {
            session.sendLine("print(6 * 7)");
            session.closeStdin();
            SessionExit exit = session.onExit().join();
            if (exit.timedOut()) {
                throw new IllegalStateException("session timed out");
            }
        }
    }

    void lineSessionScenario() {
        CommandService repl = Icli.command(CommandSpec.of("tool"));

        try (LineSession session = repl.lineSession()
                .withArgs("repl")
                .withRequestTimeout(Duration.ofSeconds(2))
                .open()) {
            LineResponse response = session.request("status");
            if (response.text().isBlank()) {
                throw new IllegalStateException("empty response");
            }
        }
    }

    void expectScenario() {
        CommandService repl = Icli.command("tool");

        try (Session session = repl.interactive().withArgs("repl").open();
                Expect expect = session.expect(ExpectOptions.defaults().withTimeout(Duration.ofSeconds(2)))) {
            expect.expectText("ready> ");
            expect.sendLine("status");
            expect.expectRegex(java.util.regex.Pattern.compile("ok|ready"));
        }
    }

    void terminalRequiredSessionScenario() {
        CommandService shell = Icli.command("sh");

        try (Session session =
                shell.interactive().withTerminal(TerminalPolicy.REQUIRED).open()) {
            session.sendLine("exit");
            SessionExit exit = session.onExit().join();
            if (exit.timedOut()) {
                session.sendSignal(TerminalSignal.INTERRUPT);
            }
        }
    }

    void listenOnlyStreamingScenario() {
        CommandService tool = Icli.command("tool");

        try (StreamSession stream = tool.listen()
                .withArgs("logs", "--follow")
                .onOutput(chunk -> {
                    if (chunk.source() == StreamSource.STDERR) {
                        System.err.print(chunk.text());
                    } else {
                        System.out.print(chunk.text());
                    }
                })
                .open()) {
            stream.onExit().join();
        }
    }

    void daemonReadinessScenario() throws InterruptedException {
        CommandService server = Icli.command("tool");
        AtomicBoolean ready = new AtomicBoolean(false);

        try (StreamSession stream = server.listen()
                .withArgs("serve")
                .withTimeout(Duration.ofSeconds(30))
                .onOutput(chunk -> {
                    if (chunk.text().contains("ready")) {
                        ready.set(true);
                    }
                })
                .open()) {
            long deadlineNanos = System.nanoTime() + Duration.ofSeconds(5).toNanos();
            while (!ready.get() && System.nanoTime() < deadlineNanos) {
                Thread.sleep(25);
            }
            if (!ready.get()) {
                throw new IllegalStateException("server did not become ready");
            }
        }
    }

    void diagnosticsScenario() {
        CommandService tool = Icli.command("tool")
                .withDiagnostics(DiagnosticsOptions.defaults().withListener(event -> {
                    if (event.attributes().containsKey("exitCode")) {
                        System.out.println(
                                event.type() + ":" + event.attributes().get("exitCode"));
                    }
                }));

        tool.run().execute("--version");
    }

    void pooledLineSessionScenario() {
        CommandService tool = Icli.command("tool");

        try (PooledLineSession pool = tool.lineSession()
                .withArgs("repl")
                .pooled()
                .withMaxSize(4)
                .withWarmupSize(1)
                .withMaxRequestsPerWorker(100)
                .withReset(worker -> worker.request("reset"))
                .open()) {
            LineResponse response = pool.request("status", Duration.ofSeconds(2));
            PooledLineSessionMetrics metrics = pool.metrics();
            if (response.text().isBlank() || metrics.size() > 4) {
                throw new IllegalStateException("unexpected pooled response");
            }
        }
    }

    void protocolSessionScenario() {
        CommandService worker = Icli.command("tool");
        ProtocolAdapter<String, String> adapter = new LengthPrefixedTextAdapter();

        try (ProtocolSession<String, String> session = worker.protocolSession(adapter)
                .withArgs("worker")
                .withRequestTimeout(Duration.ofSeconds(2))
                .withOutputBacklogLimit(128 * 1024)
                .withReadiness(ready -> ready.request("ready"))
                .open()) {
            String response = session.request("first line\nsecond line");
            if (response.isBlank()) {
                throw new IllegalStateException("empty response");
            }
        }
    }

    void pooledProtocolSessionScenario() {
        CommandService worker = Icli.command("tool");

        try (PooledProtocolSession<String, String> pool = worker.protocolSession(LengthPrefixedTextAdapter::new)
                .withArgs("worker")
                .withReadiness(ready -> ready.request("ready"))
                .pooled()
                .withMaxSize(4)
                .withWarmupSize(1)
                .withMinIdle(1)
                .open()) {
            String response = pool.request("document\nbody", Duration.ofSeconds(2));
            PooledProtocolSessionMetrics metrics = pool.metrics();
            if (response.isBlank() || metrics.size() > 4) {
                throw new IllegalStateException("unexpected pooled response");
            }
        }
    }

    void scenarioPresetComposition() {
        CommandService tool = Icli.command("tool");

        tool.run()
                .withArgs("env")
                .configuredBy(ScenarioPresets.environmentDiagnostics(Duration.ofSeconds(2), 16 * 1024))
                .execute();

        try (StreamSession stream = tool.listen()
                .withArgs("logs", "--follow")
                .onOutput(chunk -> System.out.print(chunk.text()))
                .configuredBy(ScenarioPresets.logFollowing(Duration.ZERO))
                .open()) {
            stream.close();
        }
    }

    private static final class LengthPrefixedTextAdapter implements ProtocolAdapter<String, String> {

        @Override
        public void writeRequest(String request, ProtocolWriter writer) {
            byte[] body = request.getBytes(StandardCharsets.UTF_8);
            writer.writeLine(Integer.toString(body.length));
            writer.write(body);
            writer.flush();
        }

        @Override
        public String readResponse(ProtocolReaders readers) {
            ProtocolReader stdout = readers.stdout();
            int length = Integer.parseInt(stdout.readLine(32));
            byte[] body = stdout.readExactly(length);
            return new String(body, StandardCharsets.UTF_8);
        }
    }
}
