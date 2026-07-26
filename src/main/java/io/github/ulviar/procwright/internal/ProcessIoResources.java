/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CommandExecutionException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

/**
 * Groups the three stable process streams and coordinates bundle-level close and rollback.
 *
 * @hidden
 */
public final class ProcessIoResources {

    private static final Consumer<Throwable> IGNORE_INLINE_CLOSE_FAILURE = ignored -> {};

    private final ProcessStreamResource<OutputStream> stdin;
    private final ProcessStreamResource<InputStream> stdout;
    private final ProcessStreamResource<InputStream> stderr;

    ProcessIoResources(
            ProcessStreamResource<OutputStream> stdin,
            ProcessStreamResource<InputStream> stdout,
            ProcessStreamResource<InputStream> stderr) {
        this.stdin = stdin;
        this.stdout = stdout;
        this.stderr = stderr;
    }

    public static ProcessIoResources acquire(Process process) {
        return acquire(process, BoundedCloseDispatcher.shared(), IGNORE_INLINE_CLOSE_FAILURE);
    }

    public static ProcessIoResources acquire(Process process, BoundedCloseDispatcher dispatcher) {
        return acquire(process, dispatcher, IGNORE_INLINE_CLOSE_FAILURE);
    }

    public static ProcessIoResources acquire(
            Process process,
            BoundedCloseDispatcher dispatcher,
            Consumer<? super Throwable> inlineOutputCloseFailureHandler) {
        return ProcessIoAcquisition.acquire(process, dispatcher, inlineOutputCloseFailureHandler);
    }

    public ProcessStreamResource<OutputStream> stdin() {
        return stdin;
    }

    public ProcessStreamResource<InputStream> stdout() {
        return stdout;
    }

    public ProcessStreamResource<InputStream> stderr() {
        return stderr;
    }

    public void closeAllAsync(Consumer<? super Throwable> failureHandler) {
        Objects.requireNonNull(failureHandler, "failureHandler");
        Throwable failure = null;
        try {
            stdin.closeAsync("procwright-process-stdin-close-", failureHandler);
        } catch (RuntimeException | Error dispatchFailure) {
            failure = dispatchFailure;
        }
        try {
            stdout.closeAsync("procwright-process-stdout-close-", failureHandler);
        } catch (RuntimeException | Error dispatchFailure) {
            failure = combineDispatchFailures(failure, dispatchFailure);
        }
        try {
            stderr.closeAsync("procwright-process-stderr-close-", failureHandler);
        } catch (RuntimeException | Error dispatchFailure) {
            failure = combineDispatchFailures(failure, dispatchFailure);
        }
        rethrow(failure);
    }

    public Throwable rollbackConstruction() {
        Throwable failure = dispatchConstructionRollback(stdin, "stdin");
        failure = FailureAggregation.combine(
                failure,
                dispatchConstructionRollback(stdout, "stdout"),
                "Multiple process stream construction rollbacks failed");
        return FailureAggregation.combine(
                failure,
                dispatchConstructionRollback(stderr, "stderr"),
                "Multiple process stream construction rollbacks failed");
    }

    private static Throwable dispatchConstructionRollback(ProcessStreamResource<?> resource, String streamName) {
        try {
            resource.closeAsync(
                    "procwright-construction-" + streamName + "-close-", BoundedFailureReporter::reportBestEffort);
            return null;
        } catch (RuntimeException | Error dispatchFailure) {
            return dispatchFailure;
        }
    }

    private static Throwable combineDispatchFailures(Throwable first, Throwable second) {
        return FailureAggregation.combine(first, second, "Multiple process stream close dispatches failed");
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    public Throwable awaitClose(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        CompletableFuture<ProcessStreamResource.CloseOutcome> stdinOutcome = stdin.closeOutcome();
        CompletableFuture<ProcessStreamResource.CloseOutcome> stdoutOutcome = stdout.closeOutcome();
        CompletableFuture<ProcessStreamResource.CloseOutcome> stderrOutcome = stderr.closeOutcome();
        CompletableFuture<Void> all = CompletableFuture.allOf(stdinOutcome, stdoutOutcome, stderrOutcome);
        try {
            if (timeout.isZero()) {
                all.get();
            } else {
                all.get(DurationSupport.saturatedMillis(timeout), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException interruption) {
            Thread.currentThread().interrupt();
            return new CommandExecutionException("Interrupted while closing process streams", interruption);
        } catch (TimeoutException timeoutFailure) {
            return new CommandExecutionException("Timed out while closing process streams", timeoutFailure);
        } catch (ExecutionException impossible) {
            return new AssertionError("process close completion stores failures as values", impossible);
        }
        Throwable failure = FailureAggregation.combine(
                stdinOutcome.join().failure(),
                stdoutOutcome.join().failure(),
                "Multiple process streams failed to close");
        return FailureAggregation.combine(
                failure, stderrOutcome.join().failure(), "Multiple process streams failed to close");
    }
}
