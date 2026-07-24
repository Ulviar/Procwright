/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.SuppressionSupport;
import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicBoolean;

/** Transactionally starts the pair of helper-owned process-output pumps. */
final class OutputPumpCoordinator {

    private final DefaultSession session;
    private final String owner;
    private final OutputPumpCleanup cleanup;
    private final AtomicBoolean startAttempted = new AtomicBoolean();

    OutputPumpCoordinator(DefaultSession session, String owner) {
        this(session, owner, FailureAttribution.PUMP_COMPLETION);
    }

    OutputPumpCoordinator(DefaultSession session, String owner, FailureAttribution failureAttribution) {
        this.session = Objects.requireNonNull(session, "session");
        this.owner = Objects.requireNonNull(owner, "owner");
        cleanup =
                new OutputPumpCleanup(session, owner, Objects.requireNonNull(failureAttribution, "failureAttribution"));
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
        StartupTransaction transaction = new StartupTransaction(gate, stdoutSlot, stderrSlot, abortState);
        try {
            SessionExitBarrier.Registration helperCleanup = session.registerHelperCleanup();
            transaction.own(helperCleanup);
            cleanup.installHelperCleanup(helperCleanup);

            session.claimOutputOwner(owner);
            transaction.transferOutputOwnership();

            cleanup.installCloseReservation(session.reserveOwnedOutputClose(owner, cleanup::pumpClosed));
            InputStream stdout = session.ownedStdout(owner);
            InputStream stderr = session.ownedStderr(owner);
            cleanup.observeProcessCleanup();

            Objects.requireNonNull(
                    starter.start(stdoutThreadName, gate.guard(() -> stdoutTask.run(stdout), stdoutSlot::complete)),
                    "pump starter returned null");
            Objects.requireNonNull(
                    starter.start(stderrThreadName, gate.guard(() -> stderrTask.run(stderr), stderrSlot::complete)),
                    "pump starter returned null");
            gate.commit();
        } catch (RuntimeException | Error failure) {
            transaction.rollback(failure);
            throw failure;
        }
    }

    void closeSessionPreserving(Throwable primary) {
        cleanup.closeSessionPreserving(primary);
    }

    void closeSessionPreserving(Throwable primary, Runnable afterOutputCleanup) {
        cleanup.closeSessionPreserving(primary, afterOutputCleanup);
    }

    void publishAfterOutputCleanup(Runnable publication) {
        cleanup.publishAfterOutputCleanup(publication);
    }

    void sealFailureAttribution(Throwable primary) {
        cleanup.sealFailureAttribution(Objects.requireNonNull(primary, "primary"));
    }

    void sealFailureAttribution() {
        cleanup.sealFailureAttribution();
    }

    void closeSession() {
        cleanup.closeSession();
    }

    @FunctionalInterface
    interface PumpTask {

        void run(InputStream stream);
    }

    enum FailureAttribution {
        PUMP_COMPLETION,
        SCENARIO_TERMINAL
    }

    private final class StartupTransaction {

        private final StartGate gate;
        private final PumpSlot stdoutSlot;
        private final PumpSlot stderrSlot;
        private final Runnable abortState;
        private SessionExitBarrier.Registration registration;
        private boolean outputOwnershipTransferred;

        private StartupTransaction(StartGate gate, PumpSlot stdoutSlot, PumpSlot stderrSlot, Runnable abortState) {
            this.gate = gate;
            this.stdoutSlot = stdoutSlot;
            this.stderrSlot = stderrSlot;
            this.abortState = abortState;
        }

        private void own(SessionExitBarrier.Registration helperRegistration) {
            registration = helperRegistration;
        }

        private void transferOutputOwnership() {
            outputOwnershipTransferred = true;
        }

        private void rollback(Throwable primary) {
            if (outputOwnershipTransferred) {
                cleanup.retainPrimaryPreserving(primary);
                abortStatePreserving(primary);
            }
            abortGatePreserving(primary);
            if (outputOwnershipTransferred) {
                completePumpSlotPreserving(stdoutSlot, primary);
                completePumpSlotPreserving(stderrSlot, primary);
                closeSessionPreserving(primary);
                if (!cleanup.hasCloseReservation()) {
                    dispatchUnreservedOutputClosePreserving(primary);
                }
            } else {
                rollbackRegistrationPreserving(primary);
            }
        }

        private void abortStatePreserving(Throwable primary) {
            try {
                abortState.run();
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
                cleanup.closeSessionPreserving(primary);
            } catch (Throwable closeFailure) {
                attachPreserving(primary, closeFailure);
            }
        }

        private void dispatchUnreservedOutputClosePreserving(Throwable primary) {
            try {
                cleanup.dispatchUnreservedOutputClosePreserving(primary);
            } catch (Throwable closeFailure) {
                attachPreserving(primary, closeFailure);
            }
        }

        private void rollbackRegistrationPreserving(Throwable primary) {
            if (registration == null) {
                return;
            }
            try {
                registration.rollback();
            } catch (Throwable rollbackFailure) {
                attachPreserving(primary, rollbackFailure);
            }
        }
    }

    private final class PumpSlot {

        private final AtomicBoolean completed = new AtomicBoolean();

        private void complete() {
            if (completed.compareAndSet(false, true)) {
                cleanup.pumpTaskFinished();
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

    private static void attachPreserving(Throwable primary, Throwable secondary) {
        try {
            SuppressionSupport.attach(primary, secondary);
        } catch (Throwable ignored) {
            // Optional failure bookkeeping must not stop construction rollback.
        }
    }
}
