package com.github.ulviar.icli.comparison;

import com.github.ulviar.icli.CommandService;
import com.github.ulviar.icli.command.CapturePolicy;
import com.github.ulviar.icli.command.CommandExecutionException;
import com.github.ulviar.icli.command.CommandInput;
import com.github.ulviar.icli.command.CommandResult;
import com.github.ulviar.icli.command.CommandSpec;
import com.github.ulviar.icli.command.OutputMode;
import com.github.ulviar.icli.command.RunOptions;
import com.github.ulviar.icli.integration.CommandBackedTool;
import com.github.ulviar.icli.integration.ToolCallResult;
import com.github.ulviar.icli.session.Expect;
import com.github.ulviar.icli.session.LineResponse;
import com.github.ulviar.icli.session.LineSession;
import com.github.ulviar.icli.session.PooledLineSession;
import com.github.ulviar.icli.session.Session;
import com.github.ulviar.icli.session.SessionOptions;
import com.github.ulviar.icli.session.StreamSession;
import com.github.ulviar.icli.terminal.PtyProvider;
import com.github.ulviar.icli.terminal.TerminalPolicy;
import java.time.Duration;
import java.util.List;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class IcliCandidateAdapter implements CandidateAdapter {

    @Override
    public String id() {
        return "icli";
    }

    @Override
    public String displayName() {
        return "iCLI rewrite";
    }

    @Override
    public String scope() {
        return "scenario-first library under evaluation";
    }

    @Override
    public CommandOutcome run(CommandRequest request, int captureLimit) {
        long started = System.nanoTime();
        try {
            CommandResult result = service(request.command()).run(call -> {
                call.capture(CapturePolicy.bounded(captureLimit))
                        .timeout(request.timeout())
                        .output(OutputMode.SEPARATE);
                request.environment().forEach(call::putEnvironment);
                if (request.stdin().length > 0) {
                    call.input(CommandInput.bytes(request.stdin()));
                }
            });
            return new CommandOutcome(
                    result.timedOut() ? OutcomeStatus.TIMEOUT : OutcomeStatus.PASS,
                    result.exitCode(),
                    result.timedOut(),
                    result.stdoutBytes(),
                    result.stdoutTruncated(),
                    result.stderrBytes(),
                    result.stderrTruncated(),
                    ProcessSupport.elapsedSince(started),
                    result.timedOut() ? "timeout result" : "");
        } catch (CommandExecutionException exception) {
            return ProcessSupport.failure(started, exception);
        }
    }

    @Override
    public CommandOutcome stream(CommandRequest request, int captureLimit) {
        long started = System.nanoTime();
        try {
            BoundedCapture stdout = new BoundedCapture(captureLimit);
            BoundedCapture stderr = new BoundedCapture(captureLimit);
            AtomicBoolean observedWhileRunning = new AtomicBoolean();
            AtomicReference<StreamSession> sessionRef = new AtomicReference<>();
            try (StreamSession session = service(request.command()).listen(call -> {
                call.timeout(request.timeout()).onOutput(chunk -> {
                    StreamSession current = sessionRef.get();
                    if (current == null || !current.onExit().isDone()) {
                        observedWhileRunning.set(true);
                    }
                    try {
                        switch (chunk.source()) {
                            case STDOUT -> stdout.write(chunk.text().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                            case STDERR -> stderr.write(chunk.text().getBytes(java.nio.charset.StandardCharsets.UTF_8));
                        }
                    } catch (java.io.IOException exception) {
                        throw new java.io.UncheckedIOException(exception);
                    }
                });
                request.environment().forEach(call::putEnvironment);
            })) {
                sessionRef.set(session);
                session.onExit().get(request.timeout().toMillis() + 500, TimeUnit.MILLISECONDS);
            }
            return new CommandOutcome(
                    OutcomeStatus.PASS,
                    OptionalInt.empty(),
                    false,
                    stdout.bytes(),
                    stdout.truncated(),
                    stderr.bytes(),
                    stderr.truncated(),
                    ProcessSupport.elapsedSince(started),
                    "listener callback; observedWhileRunning=" + observedWhileRunning.get());
        } catch (Exception exception) {
            return ProcessSupport.failure(started, exception);
        }
    }

    @Override
    public CommandOutcome lineSession(CommandRequest request, Duration requestTimeout) {
        long started = System.nanoTime();
        try (LineSession session = service(request.command()).lineSession(call -> {})) {
            LineResponse alpha = session.request("alpha", requestTimeout);
            LineResponse beta = session.request("beta", requestTimeout);
            String text = alpha.text() + "\n" + beta.text();
            return new CommandOutcome(
                    text.contains("response:alpha") && text.contains("response:beta")
                            ? OutcomeStatus.PASS
                            : OutcomeStatus.FAIL,
                    OptionalInt.empty(),
                    false,
                    text.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    false,
                    new byte[0],
                    false,
                    ProcessSupport.elapsedSince(started),
                    "");
        } catch (Exception exception) {
            return ProcessSupport.failure(started, exception);
        }
    }

    @Override
    public CommandOutcome expectPrompt(CommandRequest request, Duration timeout) {
        long started = System.nanoTime();
        try (Session session = service(request.command()).interactive(call -> call.idleTimeout(timeout));
                Expect expect = session.expect()) {
            expect.expectText("ready> ", timeout).sendLine("status").expectText("accepted:status", timeout);
            return new CommandOutcome(
                    OutcomeStatus.PASS,
                    OptionalInt.empty(),
                    false,
                    expect.transcript().text().getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    expect.transcript().truncated(),
                    new byte[0],
                    false,
                    ProcessSupport.elapsedSince(started),
                    "");
        } catch (Exception exception) {
            return ProcessSupport.failure(started, exception);
        }
    }

    @Override
    public CommandOutcome ptyProbe(Duration timeout) {
        long started = System.nanoTime();
        if (ProcessSupport.isWindows()) {
            return new CommandOutcome(
                    OutcomeStatus.SKIPPED,
                    OptionalInt.empty(),
                    false,
                    new byte[0],
                    false,
                    new byte[0],
                    false,
                    Duration.ZERO,
                    "POSIX shell probe skipped on Windows");
        }
        if (!PtyProvider.system().available()) {
            return new CommandOutcome(
                    OutcomeStatus.SKIPPED,
                    OptionalInt.empty(),
                    false,
                    new byte[0],
                    false,
                    new byte[0],
                    false,
                    Duration.ZERO,
                    "system PTY provider unavailable");
        }
        try (Session session = new CommandService(
                        CommandSpec.of("sh"), RunOptions.defaults(), SessionOptions.defaults())
                .interactive(call -> call.terminal(TerminalPolicy.REQUIRED)
                        .args("-c", "if [ -t 0 ] && [ -t 1 ]; then echo pty:true; else echo pty:false; fi"))) {
            byte[] output = ProcessSupport.readAllBytesBounded(session.stdout(), timeout, session::close);
            boolean exited = session.onExit()
                            .get(timeout.toMillis(), TimeUnit.MILLISECONDS)
                            .exitCode()
                            .orElse(1)
                    == 0;
            String text = new String(output, java.nio.charset.StandardCharsets.UTF_8);
            return new CommandOutcome(
                    exited && text.contains("pty:true") ? OutcomeStatus.PASS : OutcomeStatus.FAIL,
                    OptionalInt.empty(),
                    !exited,
                    output,
                    false,
                    new byte[0],
                    false,
                    ProcessSupport.elapsedSince(started),
                    text.strip());
        } catch (Exception exception) {
            return ProcessSupport.failure(started, exception);
        }
    }

    CommandOutcome pooled(List<String> command, Duration timeout) {
        long started = System.nanoTime();
        try (PooledLineSession pool =
                service(command).pooled(call -> call.maxSize(2).warmupSize(1).acquireTimeout(timeout))) {
            String first = pool.request("alpha", timeout).text();
            String second = pool.request("beta", timeout).text();
            String text = first + "\n" + second + "\ncreated:" + pool.metrics().created();
            return new CommandOutcome(
                    first.equals("response:alpha") && second.equals("response:beta")
                            ? OutcomeStatus.PASS
                            : OutcomeStatus.FAIL,
                    OptionalInt.empty(),
                    false,
                    text.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                    false,
                    new byte[0],
                    false,
                    ProcessSupport.elapsedSince(started),
                    "");
        } catch (Exception exception) {
            return ProcessSupport.failure(started, exception);
        }
    }

    CommandOutcome structuredToolObservation(List<String> successCommand, List<String> failureCommand) {
        long started = System.nanoTime();
        CommandBackedTool<String, String> tool = CommandBackedTool.of(mode -> {
            List<String> command = "success".equals(mode) ? successCommand : failureCommand;
            CommandResult result = service(command).run(call -> call.capture(CapturePolicy.bounded(4096))
                    .timeout(Duration.ofSeconds(2))
                    .output(OutputMode.SEPARATE));
            if (!result.succeeded()) {
                throw result.toException();
            }
            return result.stdout();
        });
        ToolCallResult<String> success = tool.call("success");
        ToolCallResult<String> failure = tool.call("failure");
        boolean ok = success.succeeded()
                && success.value().orElseThrow().equals("ok\n")
                && !failure.succeeded()
                && failure.error().orElseThrow().code().equals("command_failed");
        String note = ok
                ? "success and structured failure observations produced"
                : "unexpected tool result success=%s failure=%s".formatted(success, failure);
        return new CommandOutcome(
                ok ? OutcomeStatus.PASS : OutcomeStatus.FAIL,
                OptionalInt.empty(),
                false,
                note.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                false,
                new byte[0],
                false,
                ProcessSupport.elapsedSince(started),
                note);
    }

    private static CommandService service(List<String> command) {
        CommandSpec.Builder builder = CommandSpec.builder(command.getFirst());
        if (command.size() > 1) {
            builder.args(command.subList(1, command.size()));
        }
        return new CommandService(builder.build(), RunOptions.defaults());
    }
}
