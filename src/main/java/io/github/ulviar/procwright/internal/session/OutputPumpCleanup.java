/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns process-output close ordering, completion publication, and cleanup-failure settlement. */
final class OutputPumpCleanup {

    private final DefaultSession session;
    private final String owner;
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();
    private final Object lock = new Object();
    private final OutputCloseFailures closeFailures = new OutputCloseFailures();
    private final CompletableFuture<Void> cleanupCompleted = new CompletableFuture<>();
    private boolean failureAttributionSealed;
    private OutputCloseReservation.Reservation closeReservation;
    private boolean helperCleanupInstalled;
    private boolean processCleanupCompleted;
    private boolean forceOutputClose;
    private boolean stdoutCloseDispatched;
    private boolean stderrCloseDispatched;
    private int pumpTasksFinished;
    private int outputClosesCompleted;
    private boolean outputCloseFailureRecorded;
    private FailureSettlement failureSettlement = FailureSettlement.OPEN;

    OutputPumpCleanup(
            DefaultSession session, String owner, OutputPumpCoordinator.FailureAttribution failureAttribution) {
        this.session = Objects.requireNonNull(session, "session");
        this.owner = Objects.requireNonNull(owner, "owner");
        failureAttributionSealed = failureAttribution == OutputPumpCoordinator.FailureAttribution.PUMP_COMPLETION;
    }

    void installHelperCleanup(SessionExitBarrier.Registration registration) {
        Objects.requireNonNull(registration, "registration");
        synchronized (lock) {
            if (helperCleanupInstalled) {
                throw new IllegalStateException("Output helper cleanup has already been installed");
            }
            helperCleanupInstalled = true;
        }
        cleanupCompleted.whenComplete((ignored, impossible) -> completeRegistration(registration));
    }

    void installCloseReservation(OutputCloseReservation.Reservation reservation) {
        Objects.requireNonNull(reservation, "reservation");
        synchronized (lock) {
            if (closeReservation != null) {
                throw new IllegalStateException("Output close reservation has already been installed");
            }
            closeReservation = reservation;
        }
    }

    boolean hasCloseReservation() {
        synchronized (lock) {
            return closeReservation != null;
        }
    }

    void observeProcessCleanup() {
        session.observeExit((ignored, exitFailure) -> {
            closeFailures.retainFallback(exitFailure);
            processCleanupCompleted();
        });
    }

    void pumpClosed() {
        dispatchReadyCloses();
    }

    void pumpTaskFinished() {
        synchronized (lock) {
            if (pumpTasksFinished == 2) {
                throw new IllegalStateException("Output pump completion accounting overflow");
            }
            pumpTasksFinished++;
        }
        maybeFinalizeCloseFailures();
    }

    void retainPrimaryPreserving(Throwable primary) {
        closeFailures.retainPrimary(primary);
    }

    void retainFailure(Throwable failure) {
        closeFailures.retainFallback(Objects.requireNonNull(failure, "failure"));
    }

    void sealFailureAttribution(Throwable primary) {
        retainPrimaryPreserving(Objects.requireNonNull(primary, "primary"));
        sealFailureAttribution();
    }

    void sealFailureAttribution() {
        synchronized (lock) {
            failureAttributionSealed = true;
        }
        maybeFinalizeCloseFailures();
    }

    void closeSessionPreserving(Throwable primary) {
        sealFailureAttribution(Objects.requireNonNull(primary, "primary"));
        initiateSessionClose();
    }

    void closeSessionPreserving(Throwable primary, Runnable afterOutputCleanup) {
        registerPublication(Objects.requireNonNull(afterOutputCleanup, "afterOutputCleanup"));
        closeSessionPreserving(primary);
    }

    void publishAfterOutputCleanup(Runnable publication) {
        registerPublication(Objects.requireNonNull(publication, "publication"));
    }

    void closeSession() {
        sealFailureAttribution();
        rethrow(initiateSessionClose());
    }

    void dispatchUnreservedOutputClosePreserving(Throwable primary) {
        try {
            session.dispatchUnreservedOwnedOutputClose(
                    owner,
                    this::recordOutputCloseFailure,
                    this::outputCloseCompleted,
                    this::recordOutputCloseFailure,
                    this::outputCloseCompleted);
        } catch (RuntimeException | Error closeFailure) {
            closeFailures.retainFallback(closeFailure);
        }
    }

    private Throwable initiateSessionClose() {
        if (!shutdownStarted.compareAndSet(false, true)) {
            return null;
        }

        requestForcedOutputClose();
        Throwable sessionFailure = null;
        try {
            session.close();
        } catch (RuntimeException | Error failure) {
            sessionFailure = failure;
            closeFailures.retainFallback(failure);
        } finally {
            if (session.terminationPublished()) {
                processCleanupCompleted();
            }
        }
        return sessionFailure;
    }

    private void requestForcedOutputClose() {
        synchronized (lock) {
            forceOutputClose = true;
        }
        dispatchReadyCloses();
    }

    private void processCleanupCompleted() {
        synchronized (lock) {
            processCleanupCompleted = true;
        }
        dispatchReadyCloses();
        maybeFinalizeCloseFailures();
    }

    private void outputCloseCompleted() {
        synchronized (lock) {
            if (outputClosesCompleted == 2) {
                throw new IllegalStateException("Output close completion accounting overflow");
            }
            outputClosesCompleted++;
        }
        maybeFinalizeCloseFailures();
    }

    private void maybeFinalizeCloseFailures() {
        boolean finalize;
        synchronized (lock) {
            finalize = processCleanupCompleted
                    && pumpTasksFinished == 2
                    && (failureAttributionSealed || (outputClosesCompleted == 2 && !outputCloseFailureRecorded))
                    && failureSettlement == FailureSettlement.OPEN;
            if (finalize) {
                failureSettlement = FailureSettlement.FINALIZING;
            }
        }
        try {
            if (finalize) {
                closeFailures.finish();
            }
        } finally {
            if (finalize) {
                synchronized (lock) {
                    failureSettlement = FailureSettlement.FINISHED;
                }
            }
            completeCleanupIfReady();
        }
    }

    private void registerPublication(Runnable publication) {
        cleanupCompleted.whenComplete((ignored, impossible) -> publish(publication));
    }

    private void completeCleanupIfReady() {
        boolean complete;
        synchronized (lock) {
            complete = failureSettlement == FailureSettlement.FINISHED && outputClosesCompleted == 2;
        }
        if (complete) {
            cleanupCompleted.complete(null);
        }
    }

    private static void completeRegistration(SessionExitBarrier.Registration registration) {
        try {
            registration.complete();
        } catch (Throwable failure) {
            BoundedFailureReporter.reportBestEffort(failure);
        }
    }

    private static void publish(Runnable publication) {
        try {
            publication.run();
        } catch (Throwable failure) {
            BoundedFailureReporter.reportBestEffort(failure);
        }
    }

    private void dispatchReadyCloses() {
        OutputCloseReservation.Reservation reservation;
        boolean dispatchStdout;
        boolean dispatchStderr;
        synchronized (lock) {
            reservation = closeReservation;
            if (!processCleanupCompleted || reservation == null) {
                return;
            }
            dispatchStdout = !stdoutCloseDispatched
                    && (forceOutputClose || reservation.pumpClosed(OutputCloseReservation.Stream.STDOUT));
            dispatchStderr = !stderrCloseDispatched
                    && (forceOutputClose || reservation.pumpClosed(OutputCloseReservation.Stream.STDERR));
            if (dispatchStdout) {
                stdoutCloseDispatched = true;
            }
            if (dispatchStderr) {
                stderrCloseDispatched = true;
            }
        }
        if (dispatchStdout && dispatchStderr) {
            dispatchClosePair(reservation);
        } else if (dispatchStdout) {
            dispatchClose(reservation, OutputCloseReservation.Stream.STDOUT, "stdout");
        } else if (dispatchStderr) {
            dispatchClose(reservation, OutputCloseReservation.Stream.STDERR, "stderr");
        }
    }

    private void dispatchClosePair(OutputCloseReservation.Reservation reservation) {
        String threadPrefix = "procwright-" + owner.toLowerCase(Locale.ROOT);
        reservation.dispatchPair(
                threadPrefix + "-stdout-close-",
                this::recordOutputCloseFailure,
                this::outputCloseCompleted,
                threadPrefix + "-stderr-close-",
                this::recordOutputCloseFailure,
                this::outputCloseCompleted);
    }

    private void dispatchClose(
            OutputCloseReservation.Reservation reservation, OutputCloseReservation.Stream stream, String streamName) {
        reservation.dispatchClose(
                stream,
                "procwright-" + owner.toLowerCase(Locale.ROOT) + '-' + streamName + "-close-",
                this::recordOutputCloseFailure,
                this::outputCloseCompleted);
    }

    private void recordOutputCloseFailure(Throwable failure) {
        closeFailures.record(failure);
        if (failure != null) {
            synchronized (lock) {
                outputCloseFailureRecorded = true;
            }
        }
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        if (failure != null) {
            throw new IllegalStateException("Could not close helper-owned process output", failure);
        }
    }

    private enum FailureSettlement {
        OPEN,
        FINALIZING,
        FINISHED
    }
}
