package com.github.ulviar.icli.comparison;

import java.io.ByteArrayInputStream;
import java.util.OptionalInt;
import org.apache.commons.exec.CommandLine;
import org.apache.commons.exec.DefaultExecutor;
import org.apache.commons.exec.ExecuteException;
import org.apache.commons.exec.ExecuteWatchdog;
import org.apache.commons.exec.PumpStreamHandler;

final class CommonsExecAdapter implements CandidateAdapter {

    @Override
    public String id() {
        return "commons-exec";
    }

    @Override
    public String displayName() {
        return "Apache Commons Exec";
    }

    @Override
    public String scope() {
        return "general process execution with watchdog and stream handlers";
    }

    @Override
    public CommandOutcome run(CommandRequest request, int captureLimit) {
        long started = System.nanoTime();
        BoundedCapture stdout = new BoundedCapture(captureLimit);
        BoundedCapture stderr = new BoundedCapture(captureLimit);
        ExecuteWatchdog watchdog =
                ExecuteWatchdog.builder().setTimeout(request.timeout()).get();
        try {
            DefaultExecutor executor = DefaultExecutor.builder()
                    .setExecuteStreamHandler(
                            new PumpStreamHandler(stdout, stderr, new ByteArrayInputStream(request.stdin())))
                    .setWorkingDirectory(request.workingDirectory())
                    .get();
            executor.setExitValues(null);
            executor.setWatchdog(watchdog);
            int exit = executor.execute(commandLine(request), request.environment());
            return outcome(started, exit, watchdog, stdout, stderr, "");
        } catch (ExecuteException exception) {
            return outcome(started, exception.getExitValue(), watchdog, stdout, stderr, exception.getMessage());
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
        ExecuteWatchdog watchdog =
                ExecuteWatchdog.builder().setTimeout(request.timeout()).get();
        try {
            DefaultExecutor executor = DefaultExecutor.builder()
                    .setExecuteStreamHandler(
                            new PumpStreamHandler(stdout, stderr, new ByteArrayInputStream(request.stdin())))
                    .setWorkingDirectory(request.workingDirectory())
                    .get();
            executor.setExitValues(null);
            executor.setWatchdog(watchdog);
            int exit = executor.execute(commandLine(request), request.environment());
            return outcome(
                    started,
                    exit,
                    watchdog,
                    stdout,
                    stderr,
                    "PumpStreamHandler callbackObserved=" + callbackObserved.get());
        } catch (ExecuteException exception) {
            return outcome(
                    started,
                    exception.getExitValue(),
                    watchdog,
                    stdout,
                    stderr,
                    "PumpStreamHandler callbackObserved=" + callbackObserved.get());
        } catch (Exception exception) {
            return ProcessSupport.failure(started, exception);
        }
    }

    private static CommandOutcome outcome(
            long started,
            int exit,
            ExecuteWatchdog watchdog,
            BoundedCapture stdout,
            BoundedCapture stderr,
            String note) {
        boolean timedOut = watchdog.killedProcess();
        return new CommandOutcome(
                timedOut ? OutcomeStatus.TIMEOUT : OutcomeStatus.PASS,
                OptionalInt.of(exit),
                timedOut,
                stdout.bytes(),
                stdout.truncated(),
                stderr.bytes(),
                stderr.truncated(),
                ProcessSupport.elapsedSince(started),
                timedOut ? "watchdog killed process" : note);
    }

    private static CommandLine commandLine(CommandRequest request) {
        CommandLine commandLine = new CommandLine(request.command().get(0));
        request.command().stream().skip(1).forEach(argument -> commandLine.addArgument(argument, false));
        return commandLine;
    }
}
