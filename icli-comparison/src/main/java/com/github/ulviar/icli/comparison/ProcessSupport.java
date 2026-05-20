package com.github.ulviar.icli.comparison;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

final class ProcessSupport {

    private ProcessSupport() {}

    static CommandOutcome runWithProcessBuilder(CommandRequest request, int captureLimit) {
        long started = System.nanoTime();
        try {
            ProcessBuilder builder = new ProcessBuilder(request.command());
            if (request.workingDirectory() != null) {
                builder.directory(request.workingDirectory().toFile());
            }
            builder.environment().putAll(request.environment());
            Process process = builder.start();

            CompletableFuture<Void> stdin =
                    CompletableFuture.runAsync(() -> writeAndClose(process.getOutputStream(), request.stdin()));
            BoundedCapture stdout = new BoundedCapture(captureLimit);
            BoundedCapture stderr = new BoundedCapture(captureLimit);
            CompletableFuture<Void> stdoutPump =
                    CompletableFuture.runAsync(() -> copy(process.getInputStream(), stdout));
            CompletableFuture<Void> stderrPump =
                    CompletableFuture.runAsync(() -> copy(process.getErrorStream(), stderr));

            boolean exited = process.waitFor(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            awaitFuture(stdin, Duration.ofSeconds(1), !exited);
            awaitFuture(stdoutPump, Duration.ofSeconds(1), !exited);
            awaitFuture(stderrPump, Duration.ofSeconds(1), !exited);
            return new CommandOutcome(
                    exited ? OutcomeStatus.PASS : OutcomeStatus.TIMEOUT,
                    OptionalInt.of(process.exitValue()),
                    !exited,
                    stdout.bytes(),
                    stdout.truncated(),
                    stderr.bytes(),
                    stderr.truncated(),
                    elapsedSince(started),
                    exited ? "" : "process exceeded timeout");
        } catch (Exception exception) {
            return failure(started, exception);
        }
    }

    static CommandOutcome lineInteraction(List<String> command, Duration timeout) {
        long started = System.nanoTime();
        try {
            Process process = new ProcessBuilder(command).start();
            try (OutputStream stdin = process.getOutputStream()) {
                stdin.write("alpha\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                stdin.flush();
                stdin.write("beta\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
                stdin.flush();
            }
            BoundedCapture stdout = new BoundedCapture(4096);
            BoundedCapture stderr = new BoundedCapture(4096);
            CompletableFuture<Void> stdoutPump =
                    CompletableFuture.runAsync(() -> copy(process.getInputStream(), stdout));
            CompletableFuture<Void> stderrPump =
                    CompletableFuture.runAsync(() -> copy(process.getErrorStream(), stderr));
            boolean exited = process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS);
            if (!exited) {
                process.destroyForcibly();
                process.waitFor(5, TimeUnit.SECONDS);
            }
            awaitFuture(stdoutPump, Duration.ofSeconds(1), !exited);
            awaitFuture(stderrPump, Duration.ofSeconds(1), !exited);
            String text = new String(stdout.bytes(), java.nio.charset.StandardCharsets.UTF_8);
            boolean ok = text.contains("response:alpha") && text.contains("response:beta");
            return new CommandOutcome(
                    exited && ok ? OutcomeStatus.PASS : OutcomeStatus.FAIL,
                    OptionalInt.of(process.exitValue()),
                    !exited,
                    stdout.bytes(),
                    stdout.truncated(),
                    stderr.bytes(),
                    stderr.truncated(),
                    elapsedSince(started),
                    ok ? "" : "missing expected line responses");
        } catch (Exception exception) {
            return failure(started, exception);
        }
    }

    static void copy(InputStream input, OutputStream output) {
        try (input;
                output) {
            input.transferTo(output);
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        }
    }

    static byte[] readAllBytesBounded(InputStream input, Duration timeout, Runnable onTimeout) throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor(task -> {
            Thread thread = new Thread(task, "icli-comparison-reader");
            thread.setDaemon(true);
            return thread;
        });
        Future<byte[]> read = executor.submit(input::readAllBytes);
        try {
            return read.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException exception) {
            onTimeout.run();
            read.cancel(true);
            throw exception;
        } finally {
            executor.shutdownNow();
            executor.awaitTermination(1, TimeUnit.SECONDS);
        }
    }

    static void awaitFuture(CompletableFuture<Void> pump, Duration timeout, boolean allowStreamClosed)
            throws Exception {
        try {
            pump.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
        } catch (ExecutionException exception) {
            if (allowStreamClosed && isStreamClosed(exception)) {
                return;
            }
            throw exception;
        } catch (CompletionException exception) {
            if (allowStreamClosed && isStreamClosed(exception)) {
                return;
            }
            throw exception;
        }
    }

    private static boolean isStreamClosed(Throwable throwable) {
        Throwable cursor = throwable;
        while (cursor != null) {
            String message = String.valueOf(cursor.getMessage()).toLowerCase(Locale.ROOT);
            if (cursor instanceof IOException
                    && (message.equals("stream closed")
                            || message.equals("stream is closed")
                            || message.equals("bad file descriptor"))) {
                return true;
            }
            cursor = cursor.getCause();
        }
        return false;
    }

    static void writeAndClose(OutputStream output, byte[] bytes) {
        try (output) {
            if (bytes.length > 0) {
                output.write(bytes);
            }
        } catch (IOException exception) {
            throw new RuntimeException(exception);
        }
    }

    static CommandOutcome failure(long started, Throwable throwable) {
        return new CommandOutcome(
                OutcomeStatus.FAIL,
                OptionalInt.empty(),
                false,
                new byte[0],
                false,
                new byte[0],
                false,
                elapsedSince(started),
                throwable.getClass().getSimpleName() + ": " + throwable.getMessage());
    }

    static Duration elapsedSince(long started) {
        return Duration.ofNanos(System.nanoTime() - started);
    }

    static Path javaExecutable() {
        String executable = isWindows() ? "java.exe" : "java";
        return Path.of(System.getProperty("java.home"), "bin", executable);
    }

    static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase().contains("win");
    }
}
