package io.github.ulviar.procwright.comparison;

import java.io.ByteArrayInputStream;
import java.util.OptionalInt;
import java.util.concurrent.TimeUnit;
import org.zeroturnaround.exec.ProcessExecutor;
import org.zeroturnaround.exec.ProcessResult;

final class ZtExecAdapter implements CandidateAdapter {

    @Override
    public String id() {
        return "zt-exec";
    }

    @Override
    public String displayName() {
        return "ZeroTurnaround zt-exec";
    }

    @Override
    public String scope() {
        return "fluent one-shot process execution";
    }

    @Override
    public CommandOutcome run(CommandRequest request, int captureLimit) {
        long started = System.nanoTime();
        BoundedCapture stdout = new BoundedCapture(captureLimit);
        BoundedCapture stderr = new BoundedCapture(captureLimit);
        try {
            ProcessExecutor executor = new ProcessExecutor()
                    .command(request.command())
                    .redirectOutput(stdout)
                    .redirectError(stderr)
                    .redirectInput(new ByteArrayInputStream(request.stdin()))
                    .exitValueAny()
                    .environment(request.environment())
                    .timeout(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (request.workingDirectory() != null) {
                executor.directory(request.workingDirectory().toFile());
            }
            ProcessResult result = executor.execute();
            return new CommandOutcome(
                    OutcomeStatus.PASS,
                    OptionalInt.of(result.getExitValue()),
                    false,
                    stdout.bytes(),
                    stdout.truncated(),
                    stderr.bytes(),
                    stderr.truncated(),
                    ProcessSupport.elapsedSince(started),
                    "");
        } catch (java.util.concurrent.TimeoutException exception) {
            return new CommandOutcome(
                    OutcomeStatus.TIMEOUT,
                    OptionalInt.empty(),
                    true,
                    stdout.bytes(),
                    stdout.truncated(),
                    stderr.bytes(),
                    stderr.truncated(),
                    ProcessSupport.elapsedSince(started),
                    "timeout exception");
        } catch (Exception exception) {
            return ProcessSupport.failure(started, exception);
        }
    }

    @Override
    public CommandOutcome stream(CommandRequest request, int captureLimit) {
        long started = System.nanoTime();
        java.util.concurrent.atomic.AtomicBoolean callbackObserved = new java.util.concurrent.atomic.AtomicBoolean();
        BoundedCapture stdout = new BoundedCapture(captureLimit, bytes -> callbackObserved.set(true));
        BoundedCapture stderr = new BoundedCapture(captureLimit, bytes -> callbackObserved.set(true));
        try {
            ProcessExecutor executor = new ProcessExecutor()
                    .command(request.command())
                    .redirectOutput(stdout)
                    .redirectError(stderr)
                    .redirectInput(new ByteArrayInputStream(request.stdin()))
                    .exitValueAny()
                    .environment(request.environment())
                    .timeout(request.timeout().toMillis(), TimeUnit.MILLISECONDS);
            if (request.workingDirectory() != null) {
                executor.directory(request.workingDirectory().toFile());
            }
            ProcessResult result = executor.execute();
            return new CommandOutcome(
                    OutcomeStatus.PASS,
                    OptionalInt.of(result.getExitValue()),
                    false,
                    stdout.bytes(),
                    stdout.truncated(),
                    stderr.bytes(),
                    stderr.truncated(),
                    ProcessSupport.elapsedSince(started),
                    "redirectOutput callbackObserved=" + callbackObserved.get());
        } catch (java.util.concurrent.TimeoutException exception) {
            return new CommandOutcome(
                    OutcomeStatus.TIMEOUT,
                    OptionalInt.empty(),
                    true,
                    stdout.bytes(),
                    stdout.truncated(),
                    stderr.bytes(),
                    stderr.truncated(),
                    ProcessSupport.elapsedSince(started),
                    "timeout exception; callbackObserved=" + callbackObserved.get());
        } catch (Exception exception) {
            return ProcessSupport.failure(started, exception);
        }
    }
}
