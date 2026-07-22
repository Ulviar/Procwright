/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.examples;

import io.github.ulviar.procwright.CommandService;
import io.github.ulviar.procwright.Procwright;
import io.github.ulviar.procwright.command.CapturePolicy;
import io.github.ulviar.procwright.command.CharsetPolicy;
import io.github.ulviar.procwright.command.CommandResult;
import io.github.ulviar.procwright.command.CommandSpec;
import io.github.ulviar.procwright.command.ShutdownPolicy;
import io.github.ulviar.procwright.session.Expect;
import io.github.ulviar.procwright.session.LineResponse;
import io.github.ulviar.procwright.session.LineSession;
import io.github.ulviar.procwright.session.PooledLineSession;
import io.github.ulviar.procwright.session.PooledLineSessionMetrics;
import io.github.ulviar.procwright.session.PooledProtocolSession;
import io.github.ulviar.procwright.session.PooledProtocolSessionMetrics;
import io.github.ulviar.procwright.session.ProtocolSession;
import io.github.ulviar.procwright.session.Session;
import io.github.ulviar.procwright.session.SessionExit;
import io.github.ulviar.procwright.session.StreamSession;
import io.github.ulviar.procwright.session.StreamSource;
import io.github.ulviar.procwright.terminal.TerminalPolicy;
import io.github.ulviar.procwright.terminal.TerminalSignal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

final class CommandServiceApiExamples {

    void oneShotScenario() {
        CommandService git = Procwright.command("git");

        CommandResult result = git.run().withArgs("status", "--short").execute();

        if (!result.succeeded()) {
            throw result.toException();
        }
    }

    void explicitCommandConfiguration() {
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

    void interactiveScenario() {
        CommandService python = Procwright.command(CommandSpec.of("python"));

        try (Session session = python.interactive()
                .withArgs("-i")
                .withIdleTimeout(Duration.ofMinutes(5))
                .open()) {
            session.sendLine("print(6 * 7)");
            session.closeStdin();
            SessionExit exit = session.onExit().join();
            if (exit.timedOut()) {
                throw new IllegalStateException("session timed out");
            }
        }
    }

    void lineSessionScenario() {
        CommandService repl = Procwright.command(CommandSpec.of("tool"));

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

    void strictBoundedLineSessionScenario() {
        CommandService worker = Procwright.command(CommandSpec.of("tool"));

        try (LineSession session = worker.lineSession()
                .withArgs("repl")
                .withMaxRequestBytes(64 * 1024)
                .withMaxResponseChars(64 * 1024)
                .withStdoutBacklogLines(128)
                .withTranscriptLimit(16 * 1024)
                .withCharsetPolicy(CharsetPolicy.report(StandardCharsets.UTF_8))
                .open()) {
            LineResponse response = session.request("status");
            if (response.text().isBlank()) {
                throw new IllegalStateException("empty response");
            }
        }
    }

    void expectScenario() {
        CommandService repl = Procwright.command("tool");

        try (Session session = repl.interactive().withArgs("repl").open();
                Expect expect =
                        session.expect().withTimeout(Duration.ofSeconds(2)).open()) {
            expect.expectText("ready> ");
            expect.sendLine("status");
            expect.expectRegex(java.util.regex.Pattern.compile("ok|ready"));
        }
    }

    void terminalRequiredSessionScenario() {
        CommandService shell = Procwright.command("sh");

        try (Session session = shell.interactive()
                        .withArgs(
                                "-c",
                                "trap 'echo procwright-interrupted; exit 0' INT; "
                                        + "echo procwright-ready; while :; do sleep 1; done")
                        .withTerminal(TerminalPolicy.REQUIRED)
                        .withIdleTimeout(Duration.ofSeconds(10))
                        .open();
                Expect expect =
                        session.expect().withTimeout(Duration.ofSeconds(2)).open()) {
            expect.expectText("procwright-ready");
            session.sendSignal(TerminalSignal.INTERRUPT);
            expect.expectText("procwright-interrupted");

            SessionExit exit = session.onExit().orTimeout(2, TimeUnit.SECONDS).join();
            if (exit.exitCode().orElse(-1) != 0) {
                throw new IllegalStateException("terminal command did not exit cleanly");
            }
        }
    }

    void listenOnlyStreamingScenario() {
        CommandService tool = Procwright.command("tool");

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
        CommandService server = Procwright.command("tool");
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
            while (!ready.get() && !stream.onExit().isDone() && System.nanoTime() < deadlineNanos) {
                Thread.sleep(25);
            }
            if (!ready.get()) {
                throw new IllegalStateException("server did not become ready");
            }
        }
    }

    void diagnosticsScenario() {
        CommandService tool = Procwright.command("tool");

        tool.run()
                .withDiagnosticListener(event -> {
                    if (event.attributes().containsKey("exitCode")) {
                        System.out.println(
                                event.type() + ":" + event.attributes().get("exitCode"));
                    }
                })
                .withArg("--version")
                .execute();
    }

    void pooledLineSessionScenario() {
        CommandService tool = Procwright.command("tool");

        try (PooledLineSession pool = tool.lineSession()
                .withArgs("repl")
                .pooled()
                .withMaxSize(4)
                .withWarmupSize(1)
                .withMaxRequestsPerWorker(100)
                .withReset(worker -> {
                    LineResponse reset = worker.request("reset");
                    if (!reset.text().equals("reset:ok")) {
                        throw new IllegalStateException("worker reset was not acknowledged");
                    }
                })
                .open()) {
            LineResponse response = pool.request("status", Duration.ofSeconds(2));
            PooledLineSessionMetrics metrics = pool.metrics();
            if (response.text().isBlank() || metrics.size() > 4) {
                throw new IllegalStateException("unexpected pooled response");
            }
        }
    }

    void drainedPooledLineSessionScenario() {
        CommandService tool = Procwright.command("tool");
        try (PooledLineSession pool =
                tool.lineSession().withArgs("repl").pooled().withMaxSize(4).open()) {
            pool.request("status");
        }
    }

    void protocolSessionScenario() {
        CommandService worker = Procwright.command("tool");

        try (ProtocolSession<String, String> session = worker.protocolSession(LengthPrefixedTextAdapter::new)
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
        CommandService worker = Procwright.command("tool");

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
}
