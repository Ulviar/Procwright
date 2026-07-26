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
    private final StartupPurpose startupPurpose;
    private final WorkerRetirement.Action<S> closeAction;
    private S session;
    private WorkerRetirement<S> retirement;
    private boolean detachedStartup;
    private long createdAtNanos;
    private long startupNanos;
    private int requests;
    private PooledWorkerRetireReason retireReason;

    PoolWorker(WorkerRetirement.Action<S> closeAction, StartupPurpose startupPurpose) {
        this.closeAction = Objects.requireNonNull(closeAction, "closeAction");
        this.startupPurpose = Objects.requireNonNull(startupPurpose, "startupPurpose");
    }

    S session() {
        return Objects.requireNonNull(session, "worker has not completed startup");
    }

    long createdAtNanos() {
        return createdAtNanos;
    }

    long startupNanos() {
        session();
        return startupNanos;
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

    void accept(S acceptedSession, long measuredStartupNanos) {
        if (session != null) {
            throw new IllegalStateException("worker session is already accepted");
        }
        if (measuredStartupNanos < 0) {
            throw new IllegalArgumentException("worker startup duration must be non-negative");
        }
        S candidate = Objects.requireNonNull(acceptedSession, "workerFactory returned null");
        retirement = new WorkerRetirement<>(candidate, closeAction);
        session = candidate;
        createdAtNanos = System.nanoTime();
        startupNanos = measuredStartupNanos;
        startup = null;
    }

    boolean detachedStartup() {
        return detachedStartup;
    }

    void detachStartup() {
        if (startup == null || detachedStartup) {
            throw new IllegalStateException("worker has no attached startup");
        }
        detachedStartup = true;
    }

    void initiateClose() {
        retirement().initiate();
    }

    CompletableFuture<WorkerRetirement.Outcome> closeOutcome() {
        return retirement().outcome();
    }

    StartupPurpose startupPurpose() {
        return startupPurpose;
    }

    PooledWorkerRetireReason retireReason() {
        return retireReason;
    }

    void retireReason(PooledWorkerRetireReason retireReason) {
        this.retireReason = retireReason;
    }

    private WorkerRetirement<S> retirement() {
        return Objects.requireNonNull(retirement, "worker has not completed startup");
    }

    enum StartupPurpose {
        DEMAND,
        WARMUP,
        REPLENISHMENT
    }
}
