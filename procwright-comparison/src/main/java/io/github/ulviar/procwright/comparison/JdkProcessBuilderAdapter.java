package io.github.ulviar.procwright.comparison;

import java.time.Duration;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

final class JdkProcessBuilderAdapter implements CandidateAdapter {

    @Override
    public String id() {
        return "jdk-process-builder";
    }

    @Override
    public String displayName() {
        return "JDK ProcessBuilder";
    }

    @Override
    public String scope() {
        return "JDK baseline process API";
    }

    @Override
    public CommandOutcome run(CommandRequest request, int captureLimit) {
        return ProcessSupport.runWithProcessBuilder(request, captureLimit);
    }

    @Override
    public CommandOutcome stream(CommandRequest request, int captureLimit) {
        long started = System.nanoTime();
        AtomicReference<Process> processRef = new AtomicReference<>();
        AtomicBoolean observedWhileRunning = new AtomicBoolean();
        try {
            ProcessBuilder builder = new ProcessBuilder(request.command());
            builder.environment().putAll(request.environment());
            Process process = builder.start();
            processRef.set(process);
            BoundedCapture stdout = new BoundedCapture(captureLimit, bytes -> {
                Process current = processRef.get();
                if (current != null && current.isAlive()) {
                    observedWhileRunning.set(true);
                }
            });
            BoundedCapture stderr = new BoundedCapture(captureLimit, bytes -> {
                Process current = processRef.get();
                if (current != null && current.isAlive()) {
                    observedWhileRunning.set(true);
                }
            });
            CompletableFuture<Void> stdoutPump =
                    CompletableFuture.runAsync(() -> ProcessSupport.copy(process.getInputStream(), stdout));
            CompletableFuture<Void> stderrPump =
                    CompletableFuture.runAsync(() -> ProcessSupport.copy(process.getErrorStream(), stderr));
            boolean exited = process.waitFor(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            ProcessSupport.awaitFuture(stdoutPump, Duration.ofSeconds(1), !exited);
            ProcessSupport.awaitFuture(stderrPump, Duration.ofSeconds(1), !exited);
            return new CommandOutcome(
                    exited ? OutcomeStatus.PASS : OutcomeStatus.TIMEOUT,
                    exited ? OptionalInt.of(process.exitValue()) : OptionalInt.empty(),
                    !exited,
                    stdout.bytes(),
                    stdout.truncated(),
                    stderr.bytes(),
                    stderr.truncated(),
                    ProcessSupport.elapsedSince(started),
                    "manual pump listener; observedWhileRunning=" + observedWhileRunning.get());
        } catch (Exception exception) {
            return ProcessSupport.failure(started, exception);
        }
    }

    @Override
    public CommandOutcome lineSession(CommandRequest request, Duration requestTimeout) {
        return ProcessSupport.lineInteraction(request.command(), requestTimeout);
    }

    @Override
    public CommandOutcome expectPrompt(CommandRequest request, Duration timeout) {
        long started = System.nanoTime();
        try {
            Process process = new ProcessBuilder(request.command()).start();
            BoundedCapture stdout = new BoundedCapture(4096);
            java.util.concurrent.CompletableFuture<Void> reader = java.util.concurrent.CompletableFuture.runAsync(
                    () -> ProcessSupport.copy(process.getInputStream(), stdout));
            try (java.io.OutputStream stdin = process.getOutputStream()) {
                waitFor(stdout, "ready> ", timeout);
                stdin.write("status\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                stdin.flush();
            }
            boolean exited = process.waitFor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS);
            }
            ProcessSupport.awaitFuture(reader, Duration.ofSeconds(1), !exited);
            String text = stdoutText(stdout);
            boolean ok = text.contains("ready> ") && text.contains("accepted:status");
            return new CommandOutcome(
                    exited && ok ? OutcomeStatus.PASS : OutcomeStatus.FAIL,
                    java.util.OptionalInt.of(process.exitValue()),
                    !exited,
                    stdout.bytes(),
                    stdout.truncated(),
                    new byte[0],
                    false,
                    ProcessSupport.elapsedSince(started),
                    ok ? "manual expect loop" : "missing prompt or accepted response");
        } catch (Exception exception) {
            return ProcessSupport.failure(started, exception);
        }
    }

    private static void waitFor(BoundedCapture capture, String text, Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (stdoutText(capture).contains(text)) {
                return;
            }
            Thread.sleep(5);
        }
        throw new IllegalStateException("missing prompt: " + text);
    }

    private static String stdoutText(BoundedCapture capture) {
        return new String(capture.bytes(), java.nio.charset.StandardCharsets.UTF_8);
    }
}
