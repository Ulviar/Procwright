/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import io.github.ulviar.procwright.command.CommandExecutionException;
import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BiConsumer;
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
        return acquire(
                process,
                BoundedCloseDispatcher.shared(),
                BoundedLifecyclePublisher.shared(),
                IGNORE_INLINE_CLOSE_FAILURE);
    }

    public static ProcessIoResources acquire(Process process, BoundedCloseDispatcher dispatcher) {
        return acquire(process, dispatcher, BoundedLifecyclePublisher.shared(), IGNORE_INLINE_CLOSE_FAILURE);
    }

    static ProcessIoResources acquire(
            Process process, BoundedCloseDispatcher dispatcher, BoundedLifecyclePublisher lifecyclePublisher) {
        return acquire(process, dispatcher, lifecyclePublisher, IGNORE_INLINE_CLOSE_FAILURE);
    }

    public static ProcessIoResources acquire(
            Process process,
            BoundedCloseDispatcher dispatcher,
            BoundedLifecyclePublisher lifecyclePublisher,
            Consumer<? super Throwable> inlineOutputCloseFailureHandler) {
        return acquire(
                process,
                dispatcher,
                lifecyclePublisher,
                inlineOutputCloseFailureHandler,
                (failureTarget, failure) -> BoundedFailureReporter.shared().report(failureTarget, failure));
    }

    static ProcessIoResources acquire(
            Process process,
            BoundedCloseDispatcher dispatcher,
            BoundedLifecyclePublisher lifecyclePublisher,
            Consumer<? super Throwable> inlineOutputCloseFailureHandler,
            BiConsumer<BoundedFailureReporter.FailureTarget, Throwable> failureReporter) {
        return ProcessIoAcquisition.acquire(
                process, dispatcher, lifecyclePublisher, inlineOutputCloseFailureHandler, failureReporter);
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
        dispatchAll(
                () -> stdin.closeAsync("procwright-process-stdin-close-", failureHandler),
                () -> stdout.closeAsync("procwright-process-stdout-close-", failureHandler),
                () -> stderr.closeAsync("procwright-process-stderr-close-", failureHandler));
    }

    public void rollbackConstruction(Throwable primaryFailure) {
        Objects.requireNonNull(primaryFailure, "primaryFailure");
        List<Throwable> rollbackFailures;
        try {
            rollbackFailures = new ArrayList<>(6);
        } catch (OutOfMemoryError allocationFailure) {
            stdin.rollbackConstruction();
            stdout.rollbackConstruction();
            stderr.rollbackConstruction();
            attachPreserving(primaryFailure, allocationFailure);
            return;
        }
        stdin.rollbackConstruction(rollbackFailures);
        stdout.rollbackConstruction(rollbackFailures);
        stderr.rollbackConstruction(rollbackFailures);
        attachAll(primaryFailure, rollbackFailures);
    }

    private static void dispatchAll(Runnable... dispatches) {
        Throwable failure = null;
        for (Runnable dispatch : dispatches) {
            try {
                dispatch.run();
            } catch (RuntimeException | Error dispatchFailure) {
                failure = SuppressionSupport.combine(failure, dispatchFailure);
            }
        }
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
        CompletableFuture<Void> all =
                CompletableFuture.allOf(stdin.closeCompletion(), stdout.closeCompletion(), stderr.closeCompletion());
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
        Throwable failure = null;
        failure = SuppressionSupport.combine(failure, stdin.closeResult());
        failure = SuppressionSupport.combine(failure, stdout.closeResult());
        return SuppressionSupport.combine(failure, stderr.closeResult());
    }

    public static void closePairAsync(
            ProcessStreamResource<? extends Closeable> first,
            String firstThreadPrefix,
            Consumer<? super Throwable> firstFailureHandler,
            Runnable firstCompletionHandler,
            ProcessStreamResource<? extends Closeable> second,
            String secondThreadPrefix,
            Consumer<? super Throwable> secondFailureHandler,
            Runnable secondCompletionHandler) {
        ProcessStreamResource.closePairAsync(
                first,
                firstThreadPrefix,
                firstFailureHandler,
                firstCompletionHandler,
                second,
                secondThreadPrefix,
                secondFailureHandler,
                secondCompletionHandler);
    }

    private static void attachAll(Throwable primaryFailure, List<Throwable> secondaryFailures) {
        for (int index = 0; index < secondaryFailures.size(); index++) {
            try {
                SuppressionSupport.attach(primaryFailure, secondaryFailures.get(index));
            } catch (Throwable ignored) {
                // Failure decoration is best effort after mandatory rollback has completed.
            }
        }
    }

    private static void attachPreserving(Throwable primaryFailure, Throwable secondaryFailure) {
        try {
            SuppressionSupport.attach(primaryFailure, secondaryFailure);
        } catch (Throwable ignored) {
            // Mandatory rollback has completed; failure decoration remains best effort.
        }
    }
}
