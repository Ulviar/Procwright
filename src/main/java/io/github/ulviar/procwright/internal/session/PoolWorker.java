/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.session.PooledWorkerRetireReason;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

/**
 * Owns the lifecycle-local state and resources of one pooled worker.
 *
 * <p>Partition-related metadata is confined to {@link WorkerPoolState}. {@link WorkerStartup} and
 * {@link WorkerRetirement} protect their own exact-once temporal transitions.
 */
final class PoolWorker<S> {

    private WorkerStartup<S> startup;
    private StartupStage startupStage = StartupStage.QUEUED;
    private StartupPurpose startupPurpose = StartupPurpose.DEMAND;
    private S session;
    private final WorkerRetirement<S> retirement;
    private long createdAtNanos;
    private int requests;
    private PooledWorkerRetireReason retireReason;
    private boolean failureReported;

    PoolWorker(WorkerRetirement.Action<S> closeAction) {
        retirement = new WorkerRetirement<>(closeAction);
    }

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

    void accept(S acceptedSession) {
        if (session != null) {
            throw new IllegalStateException("worker session is already accepted");
        }
        S candidate = Objects.requireNonNull(acceptedSession, "workerFactory returned null");
        retirement.accept(candidate);
        session = candidate;
        createdAtNanos = System.nanoTime();
        startup = null;
    }

    void retirementAdmission(PoolLifecycleDispatcher.Admission admission) {
        retirement.admission(admission);
    }

    PoolLifecycleDispatcher.Admission retirementAdmissionOrNull() {
        return retirement.admissionOrNull();
    }

    PoolLifecycleDispatcher.Admission detachRetirementAdmission() {
        return retirement.detachAdmission();
    }

    void initiateClose() {
        retirement.initiate();
    }

    CompletableFuture<WorkerRetirement.Outcome> closeOutcome() {
        return retirement.outcome();
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
