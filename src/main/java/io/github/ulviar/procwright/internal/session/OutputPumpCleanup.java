/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import java.util.Locale;
import java.util.Objects;

/** Owns logical mode settlement and process-output close ordering. */
final class OutputPumpCleanup {

    private final DefaultSession session;
    private final SessionOutputMode outputMode;
    private final Object lock = new Object();
    private OutputCloseReservation.Reservation closeReservation;
    private SessionTerminal.ModeSettlement modeFailure;
    private boolean processOutcomeObserved;
    private boolean closeDispatched;
    private ModeLifecycle modeLifecycle = ModeLifecycle.DRAINING;
    private ShutdownLifecycle shutdownLifecycle = ShutdownLifecycle.IDLE;
    private int pumpTasksRemaining = 2;

    OutputPumpCleanup(DefaultSession session, SessionOutputMode outputMode) {
        this.session = Objects.requireNonNull(session, "session");
        this.outputMode = Objects.requireNonNull(outputMode, "outputMode");
        outputMode.requireHelper();
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

    void observeProcessCleanup() {
        session.observePrimaryOutcome((ignored, failure) -> {
            if (failure != null) {
                abandonAfterPrimaryFailure();
            }
        });
        session.observeTermination((ignored, failure) -> processOutcomeObserved());
    }

    void pumpTaskFinished() {
        SessionTerminal.ModeSettlement settlement;
        synchronized (lock) {
            if (pumpTasksRemaining == 0) {
                throw new IllegalStateException("Output pump completion accounting overflow");
            }
            pumpTasksRemaining--;
            settlement = claimSettlementLocked();
        }
        publishSettlement(settlement);
    }

    void reportFailure(Throwable failure) {
        if (failure != null) {
            BoundedFailureReporter.reportBestEffort(failure);
        }
    }

    boolean closeSessionAfterFailure(Throwable failure) {
        return closeSessionAfterFailure(failure, () -> {});
    }

    boolean closeSessionAfterFailure(Throwable failure, Runnable afterClaim) {
        Throwable terminalFailure = recordModeFailure(SessionTerminal.ModeSettlement.completed(failure));
        ShutdownResult result = initiateSessionClose(terminalFailure, false, afterClaim);
        reportFailure(result.failure());
        return result.selected();
    }

    void closeSessionAfterObservedEof(Throwable failure) {
        synchronized (lock) {
            if (modeLifecycle != ModeLifecycle.SETTLED) {
                recordModeFailureLocked(SessionTerminal.ModeSettlement.processSuccessPreferred(failure));
            }
        }
        reportFailure(initiateSessionClose(null, false, () -> {}).failure());
    }

    void closeSession() {
        closeSession(false, () -> {});
    }

    boolean closeSession(boolean timedOut, Runnable afterClaim) {
        ShutdownResult result = initiateSessionClose(null, timedOut, afterClaim);
        rethrow(result.failure());
        return result.selected();
    }

    private ShutdownResult initiateSessionClose(Throwable terminalFailure, boolean timedOut, Runnable afterClaim) {
        Objects.requireNonNull(afterClaim, "afterClaim");
        synchronized (lock) {
            if (shutdownLifecycle != ShutdownLifecycle.IDLE) {
                return new ShutdownResult(false, terminalFailure);
            }
            shutdownLifecycle = ShutdownLifecycle.STOPPING;
        }

        Throwable sessionFailure = null;
        boolean selected = false;
        try {
            if (terminalFailure == null) {
                selected = session.closeFromHelper(timedOut, afterClaim);
            } else {
                selected = session.terminateAfterHelperFailure(terminalFailure, afterClaim);
            }
        } catch (RuntimeException | Error failure) {
            sessionFailure = failure;
        } finally {
            SessionTerminal.ModeSettlement settlement;
            synchronized (lock) {
                if (shutdownLifecycle == ShutdownLifecycle.STOPPING) {
                    shutdownLifecycle = ShutdownLifecycle.STOPPED;
                }
                settlement = claimSettlementLocked();
            }
            publishSettlement(settlement);
            if (session.terminationPublished()) {
                processOutcomeObserved();
            }
        }
        return new ShutdownResult(selected, sessionFailure);
    }

    private void processOutcomeObserved() {
        SessionTerminal.ModeSettlement settlement;
        synchronized (lock) {
            processOutcomeObserved = true;
            settlement = claimSettlementLocked();
        }
        publishSettlement(settlement);
        dispatchReadyCloses();
    }

    private void abandonAfterPrimaryFailure() {
        SessionTerminal.ModeSettlement settlement;
        synchronized (lock) {
            shutdownLifecycle = ShutdownLifecycle.STOPPED;
            settlement = claimSettlementLocked();
        }
        publishSettlement(settlement);
        dispatchReadyCloses();
    }

    private SessionTerminal.ModeSettlement claimSettlementLocked() {
        if (modeLifecycle == ModeLifecycle.SETTLED) {
            return null;
        }
        boolean naturallyDrained = pumpTasksRemaining == 0;
        if (shutdownLifecycle != ShutdownLifecycle.STOPPED && !naturallyDrained) {
            return null;
        }
        modeLifecycle = ModeLifecycle.SETTLED;
        return modeFailure != null ? modeFailure : SessionTerminal.ModeSettlement.completed(null);
    }

    private void publishSettlement(SessionTerminal.ModeSettlement settlement) {
        if (settlement == null) {
            return;
        }
        session.settleOutputMode(settlement);
        dispatchReadyCloses();
    }

    private Throwable recordModeFailure(SessionTerminal.ModeSettlement failure) {
        synchronized (lock) {
            return recordModeFailureLocked(failure);
        }
    }

    private Throwable recordModeFailureLocked(SessionTerminal.ModeSettlement failure) {
        SessionTerminal.ModeSettlement terminalFailure = Objects.requireNonNull(failure, "failure");
        if (modeLifecycle == ModeLifecycle.SETTLED) {
            return terminalFailure.failure();
        }
        if (modeFailure == null) {
            modeFailure = terminalFailure;
        }
        return modeFailure.failure();
    }

    private void dispatchReadyCloses() {
        OutputCloseReservation.Reservation reservation;
        synchronized (lock) {
            reservation = closeReservation;
            if (!processOutcomeObserved
                    || modeLifecycle != ModeLifecycle.SETTLED
                    || reservation == null
                    || closeDispatched) {
                return;
            }
            closeDispatched = true;
        }
        String threadPrefix = "procwright-" + outputMode.owner().toLowerCase(Locale.ROOT);
        reservation.dispatchPair(
                threadPrefix + "-stdout-close-",
                this::reportFailure,
                threadPrefix + "-stderr-close-",
                this::reportFailure);
    }

    private static void rethrow(Throwable failure) {
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private enum ModeLifecycle {
        DRAINING,
        SETTLED
    }

    private enum ShutdownLifecycle {
        IDLE,
        STOPPING,
        STOPPED
    }

    private record ShutdownResult(boolean selected, Throwable failure) {}
}
