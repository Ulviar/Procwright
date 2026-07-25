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
    private final StreamCloseState stdoutClose = new StreamCloseState(OutputCloseReservation.Stream.STDOUT);
    private final StreamCloseState stderrClose = new StreamCloseState(OutputCloseReservation.Stream.STDERR);
    private boolean failureAttributionSealed;
    private OutputCloseReservation.Reservation closeReservation;
    private boolean helperCleanupInstalled;
    private boolean processCleanupCompleted;
    private int pumpTasksRemaining = 2;
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

    void pumpClosed(OutputCloseReservation.Stream stream) {
        synchronized (lock) {
            closeState(stream).readyToClose();
        }
        dispatchReadyCloses();
    }

    void pumpTaskFinished() {
        synchronized (lock) {
            if (pumpTasksRemaining == 0) {
                throw new IllegalStateException("Output pump completion accounting overflow");
            }
            pumpTasksRemaining--;
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
        synchronized (lock) {
            if (closeReservation != null) {
                throw new IllegalStateException("Reserved process output cannot use construction rollback close");
            }
            stdoutClose.readyToClose();
            stderrClose.readyToClose();
            stdoutClose.dispatchRequired();
            stderrClose.dispatchRequired();
        }
        try {
            session.dispatchUnreservedOwnedOutputClose(
                    owner,
                    this::recordOutputCloseFailure,
                    () -> outputCloseCompleted(stdoutClose),
                    this::recordOutputCloseFailure,
                    () -> outputCloseCompleted(stderrClose));
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
            stdoutClose.readyToClose();
            stderrClose.readyToClose();
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

    private void outputCloseCompleted(StreamCloseState closeState) {
        synchronized (lock) {
            closeState.settle();
        }
        maybeFinalizeCloseFailures();
    }

    private void maybeFinalizeCloseFailures() {
        boolean finalize;
        synchronized (lock) {
            finalize = processCleanupCompleted
                    && pumpTasksRemaining == 0
                    && (failureAttributionSealed || (outputClosesSettled() && !outputCloseFailureRecorded))
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
            complete = failureSettlement == FailureSettlement.FINISHED && outputClosesSettled();
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
            dispatchStdout = stdoutClose.dispatchIfReady();
            dispatchStderr = stderrClose.dispatchIfReady();
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
                () -> outputCloseCompleted(stdoutClose),
                threadPrefix + "-stderr-close-",
                this::recordOutputCloseFailure,
                () -> outputCloseCompleted(stderrClose));
    }

    private void dispatchClose(
            OutputCloseReservation.Reservation reservation, OutputCloseReservation.Stream stream, String streamName) {
        StreamCloseState closeState = closeState(stream);
        reservation.dispatchClose(
                stream,
                "procwright-" + owner.toLowerCase(Locale.ROOT) + '-' + streamName + "-close-",
                this::recordOutputCloseFailure,
                () -> outputCloseCompleted(closeState));
    }

    private void recordOutputCloseFailure(Throwable failure) {
        closeFailures.record(failure);
        if (failure != null) {
            synchronized (lock) {
                outputCloseFailureRecorded = true;
            }
        }
    }

    private StreamCloseState closeState(OutputCloseReservation.Stream stream) {
        return switch (Objects.requireNonNull(stream, "stream")) {
            case STDOUT -> stdoutClose;
            case STDERR -> stderrClose;
        };
    }

    private boolean outputClosesSettled() {
        return stdoutClose.settled() && stderrClose.settled();
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

    private static final class StreamCloseState {

        private final OutputCloseReservation.Stream stream;
        private State state = State.PUMP_RUNNING;

        private StreamCloseState(OutputCloseReservation.Stream stream) {
            this.stream = stream;
        }

        private void readyToClose() {
            if (state == State.PUMP_RUNNING) {
                state = State.READY_TO_CLOSE;
            }
        }

        private boolean dispatchIfReady() {
            if (state != State.READY_TO_CLOSE) {
                return false;
            }
            state = State.CLOSE_DISPATCHED;
            return true;
        }

        private void dispatchRequired() {
            if (!dispatchIfReady()) {
                throw new IllegalStateException(stream.name().toLowerCase(Locale.ROOT)
                        + " close cannot be dispatched while it is "
                        + state.name().toLowerCase(Locale.ROOT));
            }
        }

        private void settle() {
            if (state != State.CLOSE_DISPATCHED) {
                throw new IllegalStateException(stream.name().toLowerCase(Locale.ROOT)
                        + " close cannot settle while it is "
                        + state.name().toLowerCase(Locale.ROOT));
            }
            state = State.SETTLED;
        }

        private boolean settled() {
            return state == State.SETTLED;
        }

        private enum State {
            PUMP_RUNNING,
            READY_TO_CLOSE,
            CLOSE_DISPATCHED,
            SETTLED
        }
    }
}
