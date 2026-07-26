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

/** Acquires process streams and their permits as one rollback-safe transaction. */
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
            ledger = new ConstructionLedger();
        } catch (OutOfMemoryError allocationFailure) {
            stopProcessWithoutFailureDecoration(process);
            throw allocationFailure;
        }
        try {
            ledger.closeReservation = dispatcher.reserve(3);
            ledger.transferPermits();
            Object closeClaimLock = new Object();

            OutputStream stdinStream = process.getOutputStream();
            ledger.stdin.stream = stdinStream;
            ProcessStreamResource<OutputStream> stdin =
                    new ProcessStreamResource<>(stdinStream, ledger.stdin.closePermit, closeClaimLock, ignored -> {});
            ledger.stdin.resource = stdin;

            InputStream stdoutStream = process.getInputStream();
            ledger.stdout.stream = stdoutStream;
            ProcessStreamResource<InputStream> stdout = new ProcessStreamResource<>(
                    stdoutStream, ledger.stdout.closePermit, closeClaimLock, inlineOutputCloseFailureHandler);
            ledger.stdout.resource = stdout;

            InputStream stderrStream = process.getErrorStream();
            ledger.stderr.stream = stderrStream;
            ProcessStreamResource<InputStream> stderr = new ProcessStreamResource<>(
                    stderrStream, ledger.stderr.closePermit, closeClaimLock, inlineOutputCloseFailureHandler);
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

    private static void release(BoundedCloseDispatcher.Reservation reservation, List<Throwable> failures) {
        try {
            reservation.release();
        } catch (Throwable releaseFailure) {
            failures.add(releaseFailure);
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

        private BoundedCloseDispatcher.Reservation closeReservation;
        private final ResourceSlot stdin = new ResourceSlot();
        private final ResourceSlot stdout = new ResourceSlot();
        private final ResourceSlot stderr = new ResourceSlot();

        private void transferPermits() {
            Objects.requireNonNull(closeReservation, "closeReservation");
            stdin.closePermit = closeReservation.takePermit();
            stdout.closePermit = closeReservation.takePermit();
            stderr.closePermit = closeReservation.takePermit();
        }

        private void rollback(Process process, List<Throwable> failures) {
            if (closeReservation != null) {
                release(closeReservation, failures);
            }
            stopProcess(process, failures);
            stdin.rollback(failures);
            stdout.rollback(failures);
            stderr.rollback(failures);
        }
    }

    private static final class ResourceSlot {

        private BoundedCloseDispatcher.Permit closePermit;
        private Closeable stream;
        private ProcessStreamResource<? extends Closeable> resource;

        private void rollback(List<Throwable> failures) {
            if (resource != null) {
                resource.rollbackConstruction(failures);
                return;
            }
            releaseUntransferredResource(failures);
        }

        private void releaseUntransferredResource(List<Throwable> failures) {
            if (closePermit == null) {
                return;
            }
            if (stream == null) {
                try {
                    closePermit.release();
                } catch (Throwable releaseFailure) {
                    failures.add(releaseFailure);
                }
                return;
            }
            try {
                closePermit.closeInline(stream);
            } catch (Throwable closeFailure) {
                failures.add(closeFailure);
            }
        }
    }
}
