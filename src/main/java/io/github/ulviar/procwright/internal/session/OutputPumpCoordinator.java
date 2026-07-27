/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import java.io.InputStream;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Transactionally starts the pair of helper-owned process-output pumps. */
final class OutputPumpCoordinator {

    private final DefaultSession session;
    private final SessionOutputMode outputMode;
    private final OutputPumpCleanup cleanup;
    private final AtomicBoolean startAttempted = new AtomicBoolean();

    OutputPumpCoordinator(DefaultSession session, SessionOutputMode outputMode) {
        this.session = Objects.requireNonNull(session, "session");
        this.outputMode = Objects.requireNonNull(outputMode, "outputMode");
        outputMode.requireHelper();
        session.claimHelperOutput(outputMode);
        cleanup = new OutputPumpCleanup(session, outputMode);
    }

    void start(
            PumpStarter starter,
            String stdoutThreadName,
            PumpTask stdoutTask,
            String stderrThreadName,
            PumpTask stderrTask) {
        Objects.requireNonNull(starter, "starter");
        Objects.requireNonNull(stdoutThreadName, "stdoutThreadName");
        Objects.requireNonNull(stdoutTask, "stdoutTask");
        Objects.requireNonNull(stderrThreadName, "stderrThreadName");
        Objects.requireNonNull(stderrTask, "stderrTask");
        if (!startAttempted.compareAndSet(false, true)) {
            throw new IllegalStateException("Output pumps have already been started");
        }

        PumpSlot stdoutSlot = new PumpSlot();
        PumpSlot stderrSlot = new PumpSlot();
        cleanup.installCloseReservation(session.reserveOwnedOutputClose(outputMode, cleanup::pumpClosed));
        InputStream stdout = session.ownedStdout(outputMode);
        InputStream stderr = session.ownedStderr(outputMode);
        cleanup.observeProcessCleanup();

        Objects.requireNonNull(
                starter.start(
                        stdoutThreadName,
                        session.guardConstructionTask(() -> stdoutTask.run(stdout), stdoutSlot::complete)),
                "pump starter returned null");
        Objects.requireNonNull(
                starter.start(
                        stderrThreadName,
                        session.guardConstructionTask(() -> stderrTask.run(stderr), stderrSlot::complete)),
                "pump starter returned null");
        session.markHelperOutputReady(outputMode);
    }

    boolean closeSessionAfterFailure(Throwable failure) {
        return cleanup.closeSessionAfterFailure(failure);
    }

    boolean closeSessionAfterFailure(Throwable failure, Runnable afterClaim) {
        return cleanup.closeSessionAfterFailure(failure, afterClaim);
    }

    void closeSessionAfterObservedEof(Throwable failure) {
        cleanup.closeSessionAfterObservedEof(failure);
    }

    void reportFailure(Throwable failure) {
        cleanup.reportFailure(failure);
    }

    void closeSession() {
        cleanup.closeSession();
    }

    boolean closeSession(boolean timedOut, Runnable afterClaim) {
        return cleanup.closeSession(timedOut, afterClaim);
    }

    @FunctionalInterface
    interface PumpTask {

        void run(InputStream stream);
    }

    private final class PumpSlot {

        private final AtomicBoolean completed = new AtomicBoolean();

        private void complete() {
            if (completed.compareAndSet(false, true)) {
                cleanup.pumpTaskFinished();
            }
        }
    }
}
