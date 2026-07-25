/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedLifecyclePublisher;
import io.github.ulviar.procwright.internal.FailureAggregation;
import io.github.ulviar.procwright.internal.ProcessLifecycle;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;

/** Owns resources until session construction either commits or rolls back. */
final class SessionConstruction {

    private static final Duration PROCESS_CLEANUP_TIMEOUT = Duration.ofSeconds(5);

    private final Process process;
    private final Gate gate = new Gate();
    private SessionResources resources;
    private BoundedLifecyclePublisher.Reservation exitReservation;
    private BoundedLifecyclePublisher.Permit exitPublication;
    private boolean committed;

    private SessionConstruction(Process process) {
        this.process = process;
    }

    static SessionConstruction begin(Process process) {
        try {
            return new SessionConstruction(process);
        } catch (RuntimeException | Error failure) {
            throw unchecked(stopProcessPreserving(process, failure));
        }
    }

    static Throwable rollbackUnowned(Process process, Throwable primaryFailure) {
        return stopProcessPreserving(Objects.requireNonNull(process, "process"), primaryFailure);
    }

    Gate gate() {
        return gate;
    }

    void own(SessionResources resources) {
        this.resources = resources;
    }

    void own(BoundedLifecyclePublisher.Reservation reservation) {
        exitReservation = reservation;
    }

    void own(BoundedLifecyclePublisher.Permit publication) {
        exitPublication = publication;
    }

    void commit() {
        committed = true;
        gate.commit();
    }

    Throwable rollback(Throwable primaryFailure) {
        if (committed) {
            return primaryFailure;
        }
        Throwable failure = combine(primaryFailure, attempt(gate::abort));
        if (exitPublication != null) {
            failure = combine(failure, attempt(exitPublication::release));
        }
        if (exitReservation != null) {
            failure = combine(failure, attempt(exitReservation::release));
        }
        failure = stopProcessPreserving(process, failure);
        if (resources != null) {
            failure = combine(failure, resources.rollbackConstruction());
        }
        return failure;
    }

    private static Throwable stopProcessPreserving(Process process, Throwable primaryFailure) {
        return combine(primaryFailure, attempt(() -> ProcessLifecycle.forceStop(process, PROCESS_CLEANUP_TIMEOUT)));
    }

    private static Throwable attempt(Runnable cleanup) {
        try {
            cleanup.run();
            return null;
        } catch (Throwable cleanupFailure) {
            return cleanupFailure;
        }
    }

    private static Throwable combine(Throwable primaryFailure, Throwable cleanupFailure) {
        return FailureAggregation.combine(
                primaryFailure, cleanupFailure, "Session construction and rollback both failed");
    }

    static RuntimeException unchecked(Throwable failure) {
        if (failure instanceof RuntimeException runtimeFailure) {
            return runtimeFailure;
        }
        if (failure instanceof Error error) {
            throw error;
        }
        throw new AssertionError("session construction failure must be unchecked", failure);
    }

    static final class Gate {

        private final CountDownLatch decision = new CountDownLatch(1);
        private volatile boolean committed;

        Runnable guard(Runnable task) {
            return () -> {
                boolean restoreInterrupt = false;
                while (true) {
                    try {
                        decision.await();
                        break;
                    } catch (InterruptedException interruption) {
                        restoreInterrupt = true;
                    }
                }
                try {
                    if (committed) {
                        task.run();
                    }
                } finally {
                    if (restoreInterrupt) {
                        Thread.currentThread().interrupt();
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
}
