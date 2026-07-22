/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedFailureReporter;
import io.github.ulviar.procwright.internal.SuppressionSupport;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Owns output-helper claim, two stream handles, and transactional publication of a pair of pump threads.
 */
final class OutputPumpCoordinator {

    private static final ConstructionProbe NO_CONSTRUCTION_FAILURES = point -> {};
    private static final ConstructionRollback DEFAULT_CONSTRUCTION_ROLLBACK = new ConstructionRollback() {};

    private final DefaultSession session;
    private final String owner;
    private final ConstructionProbe constructionProbe;
    private final ConstructionRollback constructionRollback;
    private final AtomicBoolean startAttempted = new AtomicBoolean();
    private final AtomicBoolean shutdownStarted = new AtomicBoolean();
    private final Object outputCloseLock = new Object();
    private final OutputCloseFailures closeFailures = new OutputCloseFailures();
    private volatile InputStream stdout;
    private volatile InputStream stderr;
    private volatile OutputCloseReservation.Reservation closeReservation;
    private volatile DefaultSession.HelperCleanupRegistration helperCleanup;
    private boolean processCleanupCompleted;
    private boolean forceOutputClose;
    private boolean stdoutPumpClosed;
    private boolean stderrPumpClosed;
    private boolean stdoutCloseDispatched;
    private boolean stderrCloseDispatched;
    private int pumpTasksFinished;
    private int outputClosesCompleted;
    private boolean closeFailuresFinalized;
    private boolean closeFailuresFinishCompleted;
    private final List<Runnable> pendingOutputCleanupPublications = new ArrayList<>(1);

    OutputPumpCoordinator(DefaultSession session, String owner) {
        this(session, owner, NO_CONSTRUCTION_FAILURES, DEFAULT_CONSTRUCTION_ROLLBACK);
    }

    OutputPumpCoordinator(DefaultSession session, String owner, ConstructionProbe constructionProbe) {
        this(session, owner, constructionProbe, DEFAULT_CONSTRUCTION_ROLLBACK);
    }

    OutputPumpCoordinator(
            DefaultSession session,
            String owner,
            ConstructionProbe constructionProbe,
            ConstructionRollback constructionRollback) {
        this.session = Objects.requireNonNull(session, "session");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.constructionProbe = Objects.requireNonNull(constructionProbe, "constructionProbe");
        this.constructionRollback = Objects.requireNonNull(constructionRollback, "constructionRollback");
    }

    void start(
            PumpStarter starter,
            String stdoutThreadName,
            PumpTask stdoutTask,
            String stderrThreadName,
            PumpTask stderrTask,
            Runnable abortState) {
        Objects.requireNonNull(starter, "starter");
        Objects.requireNonNull(stdoutThreadName, "stdoutThreadName");
        Objects.requireNonNull(stdoutTask, "stdoutTask");
        Objects.requireNonNull(stderrThreadName, "stderrThreadName");
        Objects.requireNonNull(stderrTask, "stderrTask");
        Objects.requireNonNull(abortState, "abortState");
        if (!startAttempted.compareAndSet(false, true)) {
            throw new IllegalStateException("Output pumps have already been started");
        }

        StartGate gate = new StartGate();
        PumpSlot stdoutSlot = new PumpSlot();
        PumpSlot stderrSlot = new PumpSlot();
        ConstructionLedger ledger = new ConstructionLedger(gate, stdoutSlot, stderrSlot, abortState);
        try {
            constructionProbe.at(ConstructionPoint.BEFORE_HELPER_REGISTRATION);
            helperCleanup = session.registerHelperCleanup();
            ledger.own(helperCleanup);
            constructionProbe.at(ConstructionPoint.AFTER_HELPER_REGISTRATION);
            session.claimOutputOwner(owner);
            ledger.transferOutputOwnership();
            constructionProbe.at(ConstructionPoint.AFTER_OUTPUT_OWNERSHIP);
            closeReservation = session.reserveOwnedOutputClose(owner, this::pumpClosed);
            constructionProbe.at(ConstructionPoint.AFTER_OUTPUT_CLOSE_RESERVATION);
            stdout = session.ownedStdout(owner);
            constructionProbe.at(ConstructionPoint.AFTER_STDOUT_ACQUISITION);
            stderr = session.ownedStderr(owner);
            constructionProbe.at(ConstructionPoint.AFTER_STDERR_ACQUISITION);
            observeProcessCleanup();
            constructionProbe.at(ConstructionPoint.AFTER_PROCESS_OBSERVER_REGISTRATION);
            Objects.requireNonNull(
                    starter.start(stdoutThreadName, gate.guard(() -> stdoutTask.run(stdout), stdoutSlot::complete)),
                    "pump starter returned null");
            Objects.requireNonNull(
                    starter.start(stderrThreadName, gate.guard(() -> stderrTask.run(stderr), stderrSlot::complete)),
                    "pump starter returned null");
            gate.commit();
        } catch (RuntimeException | Error failure) {
            ledger.rollback(failure);
            throw failure;
        }
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
        registerOutputCleanupPublication(Objects.requireNonNull(publication, "publication"));
    }

    boolean outputCleanupCompleted() {
        synchronized (outputCloseLock) {
            return outputCleanupCompletedLocked();
        }
    }

    void closeSession() {
        rethrow(shutdown(null, null));
    }

    private Throwable shutdown(Throwable suppliedPrimary, Runnable afterOutputCleanup) {
        closeFailures.retainPrimary(suppliedPrimary);
        if (afterOutputCleanup != null) {
            registerOutputCleanupPublication(afterOutputCleanup);
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

    private void observeProcessCleanup() {
        session.observeExit((ignored, exitFailure) -> {
            closeFailures.retainFallback(exitFailure);
            processCleanupCompleted();
        });
    }

    private void pumpClosed(OutputCloseReservation.Stream stream) {
        synchronized (outputCloseLock) {
            switch (stream) {
                case STDOUT -> stdoutPumpClosed = true;
                case STDERR -> stderrPumpClosed = true;
            }
        }
        dispatchReadyCloses();
    }

    private void requestForcedOutputClose() {
        synchronized (outputCloseLock) {
            forceOutputClose = true;
        }
        dispatchReadyCloses();
    }

    private void processCleanupCompleted() {
        synchronized (outputCloseLock) {
            processCleanupCompleted = true;
        }
        dispatchReadyCloses();
        maybeFinalizeCloseFailures();
    }

    private void pumpTaskFinished() {
        synchronized (outputCloseLock) {
            if (pumpTasksFinished == 2) {
                throw new IllegalStateException("Output pump completion accounting overflow");
            }
            pumpTasksFinished++;
        }
        maybeFinalizeCloseFailures();
    }

    private void outputCloseCompleted() {
        synchronized (outputCloseLock) {
            if (outputClosesCompleted == 2) {
                throw new IllegalStateException("Output close completion accounting overflow");
            }
            outputClosesCompleted++;
        }
        maybeFinalizeCloseFailures();
    }

    private void maybeFinalizeCloseFailures() {
        boolean finalize;
        synchronized (outputCloseLock) {
            finalize = processCleanupCompleted && pumpTasksFinished == 2 && !closeFailuresFinalized;
            if (finalize) {
                closeFailuresFinalized = true;
            }
        }
        if (finalize) {
            try {
                closeFailures.finish();
            } finally {
                synchronized (outputCloseLock) {
                    closeFailuresFinishCompleted = true;
                }
            }
        }
        publishReadyOutputCleanupPublications();
        completeHelperCleanupIfReady();
    }

    private void registerOutputCleanupPublication(Runnable publication) {
        List<Runnable> ready;
        synchronized (outputCloseLock) {
            if (outputCleanupCompletedLocked()) {
                ready = List.of(publication);
            } else {
                pendingOutputCleanupPublications.add(publication);
                ready = List.of();
            }
        }
        publishAll(ready);
    }

    private void publishReadyOutputCleanupPublications() {
        List<Runnable> ready;
        synchronized (outputCloseLock) {
            if (!outputCleanupCompletedLocked() || pendingOutputCleanupPublications.isEmpty()) {
                return;
            }
            ready = List.copyOf(pendingOutputCleanupPublications);
            pendingOutputCleanupPublications.clear();
        }
        publishAll(ready);
    }

    private boolean outputCleanupCompletedLocked() {
        return closeFailuresFinishCompleted && outputClosesCompleted == 2;
    }

    private void completeHelperCleanupIfReady() {
        DefaultSession.HelperCleanupRegistration registration;
        synchronized (outputCloseLock) {
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
                io.github.ulviar.procwright.internal.Threading.reportUncaught(Thread.currentThread(), failure);
            }
        }
    }

    private void dispatchReadyCloses() {
        OutputCloseReservation.Reservation reservation;
        boolean dispatchStdout;
        boolean dispatchStderr;
        synchronized (outputCloseLock) {
            reservation = closeReservation;
            if (!processCleanupCompleted || reservation == null) {
                return;
            }
            dispatchStdout = !stdoutCloseDispatched && (forceOutputClose || stdoutPumpClosed);
            dispatchStderr = !stderrCloseDispatched && (forceOutputClose || stderrPumpClosed);
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
        String threadPrefix = "procwright-" + owner.toLowerCase(java.util.Locale.ROOT);
        reservation.dispatchPair(
                threadPrefix + "-stdout-close-",
                closeFailures::record,
                this::outputCloseCompleted,
                threadPrefix + "-stderr-close-",
                closeFailures::record,
                this::outputCloseCompleted);
    }

    private void dispatchClose(
            OutputCloseReservation.Reservation reservation, OutputCloseReservation.Stream stream, String streamName) {
        reservation.dispatchClose(
                stream,
                "procwright-" + owner.toLowerCase(java.util.Locale.ROOT) + '-' + streamName + "-close-",
                closeFailures::record,
                this::outputCloseCompleted);
    }

    private void dispatchUnreservedOutputClosePreserving(Throwable primary) {
        try {
            session.dispatchUnreservedOwnedOutputClose(
                    owner,
                    closeFailures::record,
                    this::outputCloseCompleted,
                    closeFailures::record,
                    this::outputCloseCompleted);
        } catch (RuntimeException | Error closeFailure) {
            SuppressionSupport.attach(primary, closeFailure);
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
            // Optional failure bookkeeping must not stop construction rollback.
        }
    }

    @FunctionalInterface
    interface PumpTask {

        void run(InputStream stream);
    }

    enum ConstructionPoint {
        BEFORE_HELPER_REGISTRATION,
        AFTER_HELPER_REGISTRATION,
        AFTER_OUTPUT_OWNERSHIP,
        AFTER_OUTPUT_CLOSE_RESERVATION,
        AFTER_STDOUT_ACQUISITION,
        AFTER_STDERR_ACQUISITION,
        AFTER_PROCESS_OBSERVER_REGISTRATION
    }

    @FunctionalInterface
    interface ConstructionProbe {

        void at(ConstructionPoint point);
    }

    interface ConstructionRollback {

        default void abortState(Runnable abortState) {
            abortState.run();
        }

        default void rollback(DefaultSession.HelperCleanupRegistration registration) {
            registration.rollback();
        }

        default void closeSession(OutputPumpCoordinator coordinator, Throwable primary) {
            coordinator.closeSessionPreserving(primary);
        }

        default void dispatchUnreservedOutputClose(OutputPumpCoordinator coordinator, Throwable primary) {
            coordinator.dispatchUnreservedOutputClosePreserving(primary);
        }
    }

    private final class ConstructionLedger {

        private final StartGate gate;
        private final PumpSlot stdoutSlot;
        private final PumpSlot stderrSlot;
        private final Runnable abortState;
        private DefaultSession.HelperCleanupRegistration registration;
        private boolean outputOwnershipTransferred;

        private ConstructionLedger(StartGate gate, PumpSlot stdoutSlot, PumpSlot stderrSlot, Runnable abortState) {
            this.gate = gate;
            this.stdoutSlot = stdoutSlot;
            this.stderrSlot = stderrSlot;
            this.abortState = abortState;
        }

        private void own(DefaultSession.HelperCleanupRegistration helperRegistration) {
            registration = helperRegistration;
        }

        private void transferOutputOwnership() {
            outputOwnershipTransferred = true;
        }

        private void rollback(Throwable primary) {
            if (outputOwnershipTransferred) {
                retainPrimaryPreserving(primary);
                abortStatePreserving(primary);
            }
            abortGatePreserving(primary);
            if (outputOwnershipTransferred) {
                completePumpSlotPreserving(stdoutSlot, primary);
                completePumpSlotPreserving(stderrSlot, primary);
                closeSessionPreserving(primary);
                if (closeReservation == null) {
                    dispatchUnreservedOutputClosePreserving(primary);
                }
            } else {
                rollbackRegistrationPreserving(primary);
            }
        }

        private void retainPrimaryPreserving(Throwable primary) {
            try {
                closeFailures.retainPrimary(primary);
            } catch (Throwable retainFailure) {
                attachPreserving(primary, retainFailure);
            }
        }

        private void abortStatePreserving(Throwable primary) {
            try {
                constructionRollback.abortState(abortState);
            } catch (Throwable abortFailure) {
                attachPreserving(primary, abortFailure);
            }
        }

        private void abortGatePreserving(Throwable primary) {
            try {
                gate.abort();
            } catch (Throwable abortFailure) {
                attachPreserving(primary, abortFailure);
            }
        }

        private void completePumpSlotPreserving(PumpSlot slot, Throwable primary) {
            try {
                slot.complete();
            } catch (Throwable completionFailure) {
                attachPreserving(primary, completionFailure);
            }
        }

        private void closeSessionPreserving(Throwable primary) {
            try {
                constructionRollback.closeSession(OutputPumpCoordinator.this, primary);
            } catch (Throwable closeFailure) {
                attachPreserving(primary, closeFailure);
            }
        }

        private void dispatchUnreservedOutputClosePreserving(Throwable primary) {
            try {
                constructionRollback.dispatchUnreservedOutputClose(OutputPumpCoordinator.this, primary);
            } catch (Throwable closeFailure) {
                attachPreserving(primary, closeFailure);
            }
        }

        private void rollbackRegistrationPreserving(Throwable primary) {
            if (registration == null) {
                return;
            }
            try {
                constructionRollback.rollback(registration);
            } catch (Throwable rollbackFailure) {
                attachPreserving(primary, rollbackFailure);
            }
        }
    }

    private final class PumpSlot {

        private final AtomicBoolean completed = new AtomicBoolean();

        private void complete() {
            if (completed.compareAndSet(false, true)) {
                pumpTaskFinished();
            }
        }
    }

    private static final class StartGate {

        private final CountDownLatch decision = new CountDownLatch(1);
        private volatile boolean committed;

        private Runnable guard(Runnable task, Runnable completion) {
            return () -> {
                boolean interrupted = false;
                while (true) {
                    try {
                        decision.await();
                        break;
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
                try {
                    if (committed) {
                        task.run();
                    }
                } finally {
                    try {
                        completion.run();
                    } finally {
                        if (interrupted) {
                            Thread.currentThread().interrupt();
                        }
                    }
                }
            };
        }

        private void commit() {
            committed = true;
            decision.countDown();
        }

        private void abort() {
            decision.countDown();
        }
    }

    private static final class OutputCloseFailures {

        private final Object lock = new Object();
        private final Set<Throwable> recorded = Collections.newSetFromMap(new IdentityHashMap<>());
        private final Set<Throwable> fallbacks = Collections.newSetFromMap(new IdentityHashMap<>());
        private final List<CleanupFailure> cleanupFailures = new ArrayList<>(2);
        private final List<Throwable> fallbackFailures = new ArrayList<>();
        private Throwable terminalPrimary;
        private Throwable fallbackPrimary;
        private boolean finished;
        private boolean reportFutureCleanupFailures;

        private void retainPrimary(Throwable failure) {
            if (failure == null) {
                return;
            }
            synchronized (lock) {
                if (terminalPrimary == null) {
                    terminalPrimary = failure;
                    reportFutureCleanupFailures = false;
                    for (Throwable fallbackFailure : fallbackFailures) {
                        SuppressionSupport.attach(failure, fallbackFailure);
                    }
                    for (CleanupFailure cleanupFailure : cleanupFailures) {
                        if (cleanupFailure.publication == Publication.PENDING) {
                            cleanupFailure.publication = Publication.ATTACHED;
                            SuppressionSupport.attachDirect(failure, cleanupFailure.failure);
                        }
                    }
                } else if (terminalPrimary != failure) {
                    SuppressionSupport.attach(terminalPrimary, failure);
                }
            }
        }

        private void retainFallback(Throwable failure) {
            if (failure == null) {
                return;
            }
            synchronized (lock) {
                if (!fallbacks.add(failure)) {
                    return;
                }
                fallbackFailures.add(failure);
                if (terminalPrimary != null) {
                    SuppressionSupport.attach(terminalPrimary, failure);
                } else if (fallbackPrimary == null) {
                    fallbackPrimary = failure;
                } else {
                    SuppressionSupport.attach(fallbackPrimary, failure);
                }
            }
        }

        private void record(Throwable failure) {
            if (failure == null) {
                return;
            }
            BoundedFailureReporter.FailureTarget failureTarget = BoundedFailureReporter.captureFailureTarget();
            Throwable target;
            boolean reportUncaught;
            CleanupFailure cleanupFailure;
            synchronized (lock) {
                if (!recorded.add(failure)) {
                    return;
                }
                cleanupFailure = new CleanupFailure(failure, failureTarget);
                cleanupFailures.add(cleanupFailure);
                if (terminalPrimary != null) {
                    cleanupFailure.publication = Publication.ATTACHED;
                    target = terminalPrimary;
                    reportUncaught = false;
                } else if (reportFutureCleanupFailures) {
                    cleanupFailure.publication = Publication.REPORT_CLAIMED;
                    target = null;
                    reportUncaught = true;
                } else if (finished && fallbackPrimary != null) {
                    cleanupFailure.publication = Publication.ATTACHED;
                    target = fallbackPrimary;
                    reportUncaught = false;
                } else if (finished) {
                    cleanupFailure.publication = Publication.REPORT_CLAIMED;
                    target = null;
                    reportUncaught = true;
                } else {
                    target = null;
                    reportUncaught = false;
                }
            }
            if (target != null) {
                SuppressionSupport.attachDirect(target, failure);
            }
            if (reportUncaught) {
                BoundedFailureReporter.shared().report(cleanupFailure.failureTarget, failure);
            }
        }

        private void finish() {
            Throwable target;
            List<Throwable> attachments = new ArrayList<>(2);
            List<CleanupFailure> reports = new ArrayList<>(2);
            synchronized (lock) {
                if (finished) {
                    return;
                }
                finished = true;
                target = terminalPrimary != null ? terminalPrimary : fallbackPrimary;
                reportFutureCleanupFailures = target == null;
                for (CleanupFailure cleanupFailure : cleanupFailures) {
                    if (cleanupFailure.publication != Publication.PENDING) {
                        continue;
                    }
                    if (target == null) {
                        cleanupFailure.publication = Publication.REPORT_CLAIMED;
                        reports.add(cleanupFailure);
                    } else {
                        cleanupFailure.publication = Publication.ATTACHED;
                        attachments.add(cleanupFailure.failure);
                    }
                }
            }
            attachAll(target, attachments);
            for (CleanupFailure cleanupFailure : reports) {
                BoundedFailureReporter.shared().report(cleanupFailure.failureTarget, cleanupFailure.failure);
            }
        }

        private static void attachAll(Throwable primary, List<Throwable> failures) {
            if (primary == null) {
                if (!failures.isEmpty()) {
                    throw new IllegalStateException("Output close failures have no attachment target");
                }
                return;
            }
            for (Throwable failure : failures) {
                SuppressionSupport.attachDirect(primary, failure);
            }
        }

        private static final class CleanupFailure {

            private final Throwable failure;
            private final BoundedFailureReporter.FailureTarget failureTarget;
            private Publication publication = Publication.PENDING;

            private CleanupFailure(Throwable failure, BoundedFailureReporter.FailureTarget failureTarget) {
                this.failure = failure;
                this.failureTarget = failureTarget;
            }
        }

        private enum Publication {
            PENDING,
            ATTACHED,
            REPORT_CLAIMED
        }
    }
}
