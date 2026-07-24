/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import io.github.ulviar.procwright.internal.SuppressionSupport;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Owns process-output close ordering, completion publication, and cleanup-failure settlement. */
final class OutputPumpCleanup {

    private final DefaultSession session;
    private final String owner;
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();
    private final Object lock = new Object();
    private final OutputCloseFailures closeFailures = new OutputCloseFailures();
    private final List<Runnable> pendingPublications = new ArrayList<>(1);
    private boolean failureAttributionSealed;
    private OutputCloseReservation.Reservation closeReservation;
    private SessionExitBarrier.Registration helperCleanup;
    private boolean processCleanupCompleted;
    private boolean forceOutputClose;
    private boolean stdoutCloseDispatched;
    private boolean stderrCloseDispatched;
    private int pumpTasksFinished;
    private int outputClosesCompleted;
    private boolean outputCloseFailureRecorded;
    private boolean closeFailuresFinalized;
    private boolean closeFailuresFinishCompleted;

    OutputPumpCleanup(
            DefaultSession session, String owner, OutputPumpCoordinator.FailureAttribution failureAttribution) {
        this.session = Objects.requireNonNull(session, "session");
        this.owner = Objects.requireNonNull(owner, "owner");
        failureAttributionSealed = failureAttribution == OutputPumpCoordinator.FailureAttribution.PUMP_COMPLETION;
    }

    void installHelperCleanup(SessionExitBarrier.Registration registration) {
        Objects.requireNonNull(registration, "registration");
        synchronized (lock) {
            if (helperCleanup != null) {
                throw new IllegalStateException("Output helper cleanup has already been installed");
            }
            helperCleanup = registration;
        }
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

    void pumpClosed(OutputCloseReservation.Stream stream) {
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
        try {
            closeFailures.retainPrimary(primary);
        } catch (Throwable retainFailure) {
            attachPreserving(primary, retainFailure);
        }
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
        Objects.requireNonNull(primary, "primary");
        shutdown(primary, null);
    }

    void closeSessionPreserving(Throwable primary, Runnable afterOutputCleanup) {
        Objects.requireNonNull(primary, "primary");
        Objects.requireNonNull(afterOutputCleanup, "afterOutputCleanup");
        shutdown(primary, afterOutputCleanup);
    }

    void publishAfterOutputCleanup(Runnable publication) {
        registerPublication(Objects.requireNonNull(publication, "publication"));
    }

    void closeSession() {
        rethrow(shutdown(null, null));
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
            attachPreserving(primary, closeFailure);
        }
    }

    private Throwable shutdown(Throwable suppliedPrimary, Runnable afterOutputCleanup) {
        if (suppliedPrimary == null) {
            sealFailureAttribution();
        } else {
            sealFailureAttribution(suppliedPrimary);
        }
        if (afterOutputCleanup != null) {
            registerPublication(afterOutputCleanup);
        }
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
            if (session.exitCompleted()) {
                processCleanupCompleted();
            }
        }
        return suppliedPrimary == null ? sessionFailure : null;
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
                    && !closeFailuresFinalized;
            if (finalize) {
                closeFailuresFinalized = true;
            }
        }
        if (finalize) {
            try {
                closeFailures.finish();
            } finally {
                synchronized (lock) {
                    closeFailuresFinishCompleted = true;
                }
            }
        }
        publishReadyPublications();
        completeHelperCleanupIfReady();
    }

    private void registerPublication(Runnable publication) {
        List<Runnable> ready;
        synchronized (lock) {
            if (outputCleanupCompletedLocked()) {
                ready = List.of(publication);
            } else {
                pendingPublications.add(publication);
                ready = List.of();
            }
        }
        publishAll(ready);
    }

    private void publishReadyPublications() {
        List<Runnable> ready;
        synchronized (lock) {
            if (!outputCleanupCompletedLocked() || pendingPublications.isEmpty()) {
                return;
            }
            ready = List.copyOf(pendingPublications);
            pendingPublications.clear();
        }
        publishAll(ready);
    }

    private boolean outputCleanupCompletedLocked() {
        return closeFailuresFinishCompleted && outputClosesCompleted == 2;
    }

    private void completeHelperCleanupIfReady() {
        SessionExitBarrier.Registration registration;
        synchronized (lock) {
            if (!outputCleanupCompletedLocked()) {
                return;
            }
            registration = helperCleanup;
        }
        if (registration != null) {
            registration.complete();
        }
    }

    private static void publishAll(List<Runnable> publications) {
        for (Runnable publication : publications) {
            try {
                publication.run();
            } catch (Throwable failure) {
                reportPublicationFailure(failure);
            }
        }
    }

    private static void reportPublicationFailure(Throwable failure) {
        try {
            BoundedFailureReporter.shared().report(Thread.currentThread(), failure);
        } catch (Throwable ignored) {
            // Best-effort reporting must not replace mandatory output cleanup.
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

    private static void attachPreserving(Throwable primary, Throwable secondary) {
        try {
            SuppressionSupport.attach(primary, secondary);
        } catch (Throwable ignored) {
            // Optional failure bookkeeping must not stop physical cleanup.
        }
    }
}
