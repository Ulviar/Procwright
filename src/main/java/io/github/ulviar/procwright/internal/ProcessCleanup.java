/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.io.Closeable;
import java.time.Duration;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Provides rollback cleanup to runtime packages outside the internal process package.
 *
 * @hidden
 */
public final class ProcessCleanup {

    private ProcessCleanup() {}

    /**
     * Performs one bounded process-tree force-stop and attempts to schedule every stream close on the shared bounded
     * close owner.
     *
     * <p>Cleanup failures are reported best-effort and never replace the outcome that triggered rollback. Stream
     * accessors and physical closes never run on the caller thread. A close may be skipped when bounded dispatch
     * capacity is exhausted or its worker cannot start.
     *
     * @param process process whose ownership is being rolled back
     * @param timeout process-tree cleanup budget
     */
    public static void forceStopAndCloseAsync(Process process, Duration timeout) {
        forceStopAndCloseAsync(
                process,
                timeout,
                ProcessLifecycle::forceStop,
                BoundedCloseDispatcher.shared(),
                BoundedFailureReporter::reportBestEffort);
    }

    static void forceStopAndCloseAsync(
            Process process,
            Duration timeout,
            ForceStop forceStop,
            BoundedCloseDispatcher closeDispatcher,
            Consumer<? super Throwable> failureReporter) {
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(timeout, "timeout");
        Objects.requireNonNull(forceStop, "forceStop");
        Objects.requireNonNull(closeDispatcher, "closeDispatcher");
        Objects.requireNonNull(failureReporter, "failureReporter");

        report(failureReporter, attempt(() -> forceStop.forceStop(process, timeout)));
        scheduleStreamCloses(process, closeDispatcher, failureReporter);
    }

    private static void scheduleStreamCloses(
            Process process, BoundedCloseDispatcher dispatcher, Consumer<? super Throwable> failureReporter) {
        dispatch(dispatcher, process::getOutputStream, "stdin", failureReporter);
        dispatch(dispatcher, process::getInputStream, "stdout", failureReporter);
        dispatch(dispatcher, process::getErrorStream, "stderr", failureReporter);
    }

    private static void dispatch(
            BoundedCloseDispatcher dispatcher,
            Supplier<? extends Closeable> stream,
            String streamName,
            Consumer<? super Throwable> failureReporter) {
        Closeable closeOperation = () ->
                Objects.requireNonNull(stream.get(), "process " + streamName).close();
        try {
            dispatcher.dispatch(BoundedCloseDispatcher.ownedCloseRequest(
                    closeOperation,
                    "procwright-pty-" + streamName + "-close-",
                    ignored -> {},
                    failure -> report(failureReporter, failure),
                    () -> {}));
        } catch (RuntimeException | Error dispatchFailure) {
            report(failureReporter, dispatchFailure);
        }
    }

    private static Throwable attempt(Action action) {
        try {
            action.run();
            return null;
        } catch (Throwable failure) {
            return failure;
        }
    }

    private static void report(Consumer<? super Throwable> failureReporter, Throwable failure) {
        if (failure == null) {
            return;
        }
        try {
            failureReporter.accept(failure);
        } catch (Throwable ignored) {
            // Rollback reporting cannot replace the failure that triggered cleanup.
        }
    }

    @FunctionalInterface
    interface ForceStop {

        void forceStop(Process process, Duration timeout);
    }

    @FunctionalInterface
    private interface Action {

        void run() throws Exception;
    }
}
