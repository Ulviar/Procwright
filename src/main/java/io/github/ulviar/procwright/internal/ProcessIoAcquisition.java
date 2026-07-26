/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal;

import java.io.Closeable;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

/** Acquires stable process streams as one rollback-safe transaction. */
final class ProcessIoAcquisition {

    private static final Duration FAILURE_CLEANUP_TIMEOUT = Duration.ofSeconds(5);

    private ProcessIoAcquisition() {}

    static ProcessIoResources acquire(
            Process process,
            BoundedCloseDispatcher dispatcher,
            Consumer<? super Throwable> inlineOutputCloseFailureHandler) {
        Objects.requireNonNull(process, "process");
        Objects.requireNonNull(dispatcher, "dispatcher");
        Objects.requireNonNull(inlineOutputCloseFailureHandler, "inlineOutputCloseFailureHandler");

        List<Throwable> cleanupFailures;
        ConstructionLedger ledger;
        try {
            cleanupFailures = new ArrayList<>(6);
            ledger = new ConstructionLedger(dispatcher);
        } catch (OutOfMemoryError allocationFailure) {
            stopProcessWithoutFailureDecoration(process);
            throw allocationFailure;
        }
        try {
            Object closeClaimLock = new Object();

            OutputStream stdinStream = process.getOutputStream();
            ledger.stdin.stream = stdinStream;
            ProcessStreamResource<OutputStream> stdin =
                    new ProcessStreamResource<>(stdinStream, dispatcher, closeClaimLock, ignored -> {});
            ledger.stdin.resource = stdin;

            InputStream stdoutStream = process.getInputStream();
            ledger.stdout.stream = stdoutStream;
            ProcessStreamResource<InputStream> stdout = new ProcessStreamResource<>(
                    stdoutStream, dispatcher, closeClaimLock, inlineOutputCloseFailureHandler);
            ledger.stdout.resource = stdout;

            InputStream stderrStream = process.getErrorStream();
            ledger.stderr.stream = stderrStream;
            ProcessStreamResource<InputStream> stderr = new ProcessStreamResource<>(
                    stderrStream, dispatcher, closeClaimLock, inlineOutputCloseFailureHandler);
            ledger.stderr.resource = stderr;

            return new ProcessIoResources(stdin, stdout, stderr);
        } catch (RuntimeException | Error failure) {
            ledger.rollback(process, cleanupFailures);
            rethrow(FailureAggregation.combine(
                    failure,
                    FailureAggregation.combine(cleanupFailures, "Process I/O acquisition cleanup failed"),
                    "Process I/O acquisition and cleanup both failed"));
            throw new AssertionError("unreachable");
        }
    }

    private static void stopProcess(Process process, List<Throwable> failures) {
        try {
            ProcessLifecycle.forceStop(process, FAILURE_CLEANUP_TIMEOUT);
        } catch (Throwable cleanupFailure) {
            failures.add(cleanupFailure);
        }
    }

    private static void stopProcessWithoutFailureDecoration(Process process) {
        try {
            ProcessLifecycle.forceStop(process, FAILURE_CLEANUP_TIMEOUT);
        } catch (Throwable ignored) {
            // The allocation failure remains primary; no bookkeeping is safe on this path.
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            throw runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError("Process I/O acquisition produced a checked failure", failure);
    }

    private static final class ConstructionLedger {

        private final ResourceSlot stdin;
        private final ResourceSlot stdout;
        private final ResourceSlot stderr;

        private ConstructionLedger(BoundedCloseDispatcher dispatcher) {
            stdin = new ResourceSlot("stdin", dispatcher);
            stdout = new ResourceSlot("stdout", dispatcher);
            stderr = new ResourceSlot("stderr", dispatcher);
        }

        private void rollback(Process process, List<Throwable> failures) {
            stopProcess(process, failures);
            stdin.rollback(failures);
            stdout.rollback(failures);
            stderr.rollback(failures);
        }
    }

    private static final class ResourceSlot {

        private final String name;
        private final BoundedCloseDispatcher dispatcher;
        private Closeable stream;
        private ProcessStreamResource<? extends Closeable> resource;

        private ResourceSlot(String name, BoundedCloseDispatcher dispatcher) {
            this.name = name;
            this.dispatcher = dispatcher;
        }

        private void rollback(List<Throwable> failures) {
            if (resource != null) {
                addIfPresent(
                        failures, resource.rollbackConstructionAsync("procwright-acquisition-" + name + "-close-"));
            } else if (stream != null) {
                try {
                    dispatcher.dispatch(BoundedCloseDispatcher.ownedCloseRequest(
                            stream,
                            "procwright-acquisition-" + name + "-close-",
                            ignored -> {},
                            BoundedFailureReporter::reportBestEffort,
                            () -> {}));
                } catch (RuntimeException | Error dispatchFailure) {
                    failures.add(dispatchFailure);
                }
            }
        }

        private static void addIfPresent(List<? super Throwable> failures, Throwable failure) {
            if (failure != null) {
                failures.add(failure);
            }
        }
    }
}
