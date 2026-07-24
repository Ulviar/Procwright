/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Owns the lifecycle-local state and resources of one pooled worker.
 *
 * <p>Mutable startup and retirement fields are confined to the owning pool monitor.
 */
final class PoolWorker<S> {

    private WorkerStartup<S> startup;
    private StartupStage startupStage = StartupStage.QUEUED;
    private StartupPurpose startupPurpose = StartupPurpose.DEMAND;
    private S session;
    private WorkerRetirement<S> retirement;
    private PoolLifecycleDispatcher.Admission retirementAdmission;
    private long createdAtNanos;
    private int requests;
    private PooledWorkerRetireReason retireReason;
    private boolean failureReported;

    S session() {
        return Objects.requireNonNull(session, "worker has not completed startup");
    }

    long createdAtNanos() {
        return createdAtNanos;
    }

    int requests() {
        return requests;
    }

    void recordRequest() {
        requests++;
    }

    WorkerStartup<S> startup() {
        return Objects.requireNonNull(startup, "worker has no startup owner");
    }

    void startup(WorkerStartup<S> startup) {
        if (this.startup != null) {
            throw new IllegalStateException("worker startup owner is already assigned");
        }
        this.startup = Objects.requireNonNull(startup, "startup");
    }

    void accept(S acceptedSession, WorkerRetirement.Action<S> closeAction) {
        if (session != null) {
            throw new IllegalStateException("worker session is already accepted");
        }
        session = Objects.requireNonNull(acceptedSession, "workerFactory returned null");
        retirement = new WorkerRetirement<>(
                session,
                Objects.requireNonNull(retirementAdmission, "worker has no retirement admission"),
                closeAction);
        createdAtNanos = System.nanoTime();
        startup = null;
    }

    void retirementAdmission(PoolLifecycleDispatcher.Admission admission) {
        if (retirementAdmission != null) {
            throw new IllegalStateException("worker retirement admission is already owned");
        }
        retirementAdmission = Objects.requireNonNull(admission, "admission");
    }

    void releaseRetirementAdmission() {
        PoolLifecycleDispatcher.Admission owned = retirementAdmission;
        retirementAdmission = null;
        if (owned != null) {
            owned.close();
        }
    }

    void initiateClose() {
        Objects.requireNonNull(retirement, "worker has no retirement owner").initiate();
    }

    CompletableFuture<WorkerRetirement.Outcome> closeOutcome() {
        return Objects.requireNonNull(retirement, "worker has no retirement owner")
                .outcome();
    }

    StartupPurpose startupPurpose() {
        return startupPurpose;
    }

    void startupPurpose(StartupPurpose startupPurpose) {
        this.startupPurpose = Objects.requireNonNull(startupPurpose, "startupPurpose");
    }

    StartupStage startupStage() {
        return startupStage;
    }

    void startupStage(StartupStage startupStage) {
        this.startupStage = Objects.requireNonNull(startupStage, "startupStage");
    }

    PooledWorkerRetireReason retireReason() {
        return retireReason;
    }

    void retireReason(PooledWorkerRetireReason retireReason) {
        this.retireReason = retireReason;
    }

    boolean claimFailureReport() {
        if (failureReported) {
            return false;
        }
        failureReported = true;
        return true;
    }

    enum StartupPurpose {
        DEMAND,
        WARMUP,
        REPLENISHMENT
    }

    enum StartupStage {
        QUEUED,
        RUNNING
    }
}
