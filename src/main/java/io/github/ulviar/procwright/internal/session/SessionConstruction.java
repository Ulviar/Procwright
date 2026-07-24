/* SPDX-License-Identifier: Apache-2.0 */

package io.github.ulviar.procwright.internal.session;

import io.github.ulviar.procwright.internal.BoundedLifecyclePublisher;
import io.github.ulviar.procwright.internal.ProcessLifecycle;
import io.github.ulviar.procwright.internal.SuppressionSupport;
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
            stopProcessPreserving(process, failure);
            throw failure;
        }
    }

    static void rollbackUnowned(Process process, Throwable primaryFailure) {
        stopProcessPreserving(Objects.requireNonNull(process, "process"), primaryFailure);
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

    void rollback(Throwable primaryFailure) {
        if (committed) {
            return;
        }
        preserving(primaryFailure, gate::abort);
        if (exitPublication != null) {
            preserving(primaryFailure, exitPublication::release);
        }
        if (exitReservation != null) {
            preserving(primaryFailure, exitReservation::release);
        }
        stopProcessPreserving(process, primaryFailure);
        if (resources != null) {
            preserving(primaryFailure, () -> resources.rollbackConstruction(primaryFailure));
        }
    }

    private static void stopProcessPreserving(Process process, Throwable primaryFailure) {
        preserving(primaryFailure, () -> ProcessLifecycle.forceStop(process, PROCESS_CLEANUP_TIMEOUT));
    }

    private static void preserving(Throwable primaryFailure, Runnable cleanup) {
        try {
            cleanup.run();
        } catch (Throwable cleanupFailure) {
            try {
                SuppressionSupport.attach(primaryFailure, cleanupFailure);
            } catch (Throwable ignored) {
                // Failure bookkeeping must not interrupt the remaining rollback.
            }
        }
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
